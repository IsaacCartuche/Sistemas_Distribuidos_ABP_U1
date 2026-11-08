package com.empresa.inventario.inbox;

import com.empresa.inventario.application.InventarioService;
import com.empresa.inventario.domain.*;
import com.empresa.inventario.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class ProcesadorReservas {
    private final InboxRepository inbox;
    private final SolicitudReservaRepository solicitudes;
    private final AsientoRepository asientos;
    private final RetencionRepository retenciones;
    private final InventarioService inventario;
    private final ObjectMapper mapper;
    public record Resultado(String codigo, boolean duplicado) { }
    public ProcesadorReservas(InboxRepository inbox, SolicitudReservaRepository solicitudes, AsientoRepository asientos,
                              RetencionRepository retenciones, InventarioService inventario, ObjectMapper mapper) {
        this.inbox = inbox; this.solicitudes = solicitudes; this.asientos = asientos;
        this.retenciones = retenciones; this.inventario = inventario; this.mapper = mapper;
    }

    @Transactional
    public Resultado procesar(EventoReserva evento) {
        String huella = huella(evento);
        if (!inbox.registrar(evento.eventId(), evento.tipo(), huella)) {
            var anterior = inbox.obtener(evento.eventId());
            if (!huella.equals(anterior.huella())) throw new EventoReservaInvalido("EVENT_ID_REUTILIZADO_CON_OTROS_DATOS");
            if (anterior.resultado() == null) throw new IllegalStateException("Inbox sin resultado confirmado");
            return new Resultado(anterior.resultado(), true);
        }
        boolean nueva = solicitudes.insertar(evento);
        var posible = solicitudes.bloquearReserva(evento.claveReserva());
        String resultado;
        if (posible.isEmpty()) resultado = "TOKEN_DE_OTRA_RESERVA";
        else if (!posible.get().corresponde(evento)) resultado = "IDENTIDAD_INCOHERENTE";
        else if (nueva && retenciones.existsById(evento.token())) {
            // No apropiarse de una retención REST preexistente que no tenía esta asociación.
            solicitudes.estado(evento.claveReserva(), "RECHAZADA", null);
            resultado = "TOKEN_PREEXISTENTE_SIN_ASOCIACION";
        } else if (evento.tipo().equals("reserva.cancelada")) resultado = cancelar(evento, posible.get());
        else resultado = crear(evento, posible.get());
        inbox.finalizar(evento.eventId(), resultado);
        return new Resultado(resultado, false);
    }

    private String crear(EventoReserva evento, SolicitudReservaRepository.Solicitud solicitud) {
        if (solicitud.estado().equals("CANCELADA")) return "RESERVA_YA_CANCELADA";
        if (solicitud.estado().equals("RECHAZADA")) return "SOLICITUD_YA_RECHAZADA";
        var asiento = asientos.findByVueloIdAndNumeroAsiento(evento.vueloId(), evento.asiento());
        if (asiento.isEmpty()) {
            solicitudes.estado(evento.claveReserva(), "RECHAZADA", null);
            return "ASIENTO_INEXISTENTE";
        }
        Asiento a = asiento.get();
        var retencion = retenciones.findById(evento.token());
        if (retencion.isPresent()) {
            Retencion r = retencion.get();
            if (!r.getAsientoId().equals(a.getId())) return "IDENTIDAD_INCOHERENTE";
            if (r.getEstado() == EstadoRetencion.CONFIRMADA) return "YA_CONFIRMADA";
            if (r.getEstado() != EstadoRetencion.ACTIVA || !r.getExpiraEn().isAfter(inventario.ahora())) {
                return "RETENCION_TERMINAL";
            }
        } else if (solicitud.estado().equals("BLOQUEADA")) return "RETENCION_AUSENTE";
        else if (!a.disponible(inventario.ahora())) {
            solicitudes.estado(evento.claveReserva(), "RECHAZADA", null);
            return "ASIENTO_NO_DISPONIBLE";
        }
        // El servicio existente es el único que bloquea, aplica TTL y hace flush optimista.
        inventario.bloquear(a.getId(), evento.token());
        solicitudes.estado(evento.claveReserva(), "BLOQUEADA", a.getId());
        return "ASIENTO_BLOQUEADO";
    }

    private String cancelar(EventoReserva evento, SolicitudReservaRepository.Solicitud solicitud) {
        if (solicitud.estado().equals("CANCELADA")) return "RESERVA_YA_CANCELADA";
        var retencion = retenciones.findById(evento.token());
        if (retencion.isEmpty()) {
            solicitudes.estado(evento.claveReserva(), "CANCELADA", solicitud.asientoId());
            return "CANCELACION_REGISTRADA_SIN_RETENCION";
        }
        if (solicitud.asientoId() == null || !retencion.get().getAsientoId().equals(solicitud.asientoId())) {
            return "IDENTIDAD_INCOHERENTE";
        }
        if (retencion.get().getEstado() == EstadoRetencion.CONFIRMADA) {
            solicitudes.estado(evento.claveReserva(), "CANCELADA", solicitud.asientoId());
            return "RESERVADO_NO_LIBERADO"; // Falta protocolo de compensación: jamás deshacer la reserva aquí.
        }
        inventario.liberar(solicitud.asientoId(), evento.token());
        solicitudes.estado(evento.claveReserva(), "CANCELADA", solicitud.asientoId());
        return "RETENCION_LIBERADA_O_TERMINAL";
    }

    private String huella(EventoReserva evento) {
        try {
            var json = mapper.createObjectNode();
            json.put("eventId", evento.eventId().toString());
            json.put("correlationId", evento.correlationId().toString());
            json.put("timestamp", evento.timestamp().toString());
            json.put("tipo", evento.tipo());
            json.set("reservaId", evento.reservaId());
            json.put("vueloId", evento.vueloId());
            json.put("asiento", evento.asiento());
            json.put("token", evento.token().toString());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(json)));
        } catch (java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("No se pudo calcular identidad del mensaje", exception);
        }
    }
}

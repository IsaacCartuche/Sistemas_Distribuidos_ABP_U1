package com.empresa.inventario.inbox;

import com.empresa.inventario.application.InventarioService;
import com.empresa.inventario.domain.*;
import com.empresa.inventario.persistence.*;
import com.empresa.inventario.outbox.ResultadosSagaOutbox;
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
    private final ResultadosSagaOutbox resultados;
    public record Resultado(String codigo, boolean duplicado) { }
    public ProcesadorReservas(InboxRepository inbox, SolicitudReservaRepository solicitudes, AsientoRepository asientos,
                              RetencionRepository retenciones, InventarioService inventario, ObjectMapper mapper,
                              ResultadosSagaOutbox resultados) {
        this.inbox = inbox; this.solicitudes = solicitudes; this.asientos = asientos;
        this.retenciones = retenciones; this.inventario = inventario; this.mapper = mapper;
        this.resultados = resultados;
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
        if (evento.tipo().equals("asiento.confirmacion_solicitada")) {
            String resultado = confirmar(evento);
            inbox.finalizar(evento.eventId(), resultado);
            return new Resultado(resultado, false);
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
        if (evento.tipo().equals("reserva.creada") && !resultado.equals("ASIENTO_BLOQUEADO")
                && !resultado.equals("YA_CONFIRMADA")) {
            Integer id = evento.asientoId() != null ? evento.asientoId()
                    : asientos.findByVueloIdAndNumeroAsiento(evento.vueloId(), evento.asiento()).map(Asiento::getId).orElse(null);
            resultados.registrar(evento, "asiento.no_disponible", id, motivoBloqueo(resultado));
        }
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
        if (evento.asientoId() != null && !evento.asientoId().equals(a.getId())) return "SOLICITUD_INVALIDA";
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
        if (retencion.isEmpty()) resultados.registrar(evento, "asiento.bloqueado", a.getId(), null);
        return "ASIENTO_BLOQUEADO";
    }

    private String cancelar(EventoReserva evento, SolicitudReservaRepository.Solicitud solicitud) {
        if (solicitud.estado().equals("CANCELADA")) return "RESERVA_YA_CANCELADA";
        var retencion = retenciones.findById(evento.token());
        if (retencion.isEmpty()) {
            solicitudes.estado(evento.claveReserva(), "CANCELADA", solicitud.asientoId());
            return "CANCELACION_REGISTRADA_SIN_RETENCION";
        }
        if (solicitud.asientoId() == null || !retencion.get().getAsientoId().equals(solicitud.asientoId())
                || (evento.asientoId() != null && !evento.asientoId().equals(solicitud.asientoId()))) {
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

    private String confirmar(EventoReserva evento) {
        var posible = solicitudes.bloquearReserva(evento.claveReserva());
        if (posible.isEmpty()) return rechazarConfirmacion(evento,
                retenciones.existsById(evento.token()) ? "RESERVA_NO_COINCIDE" : "RETENCION_INEXISTENTE");
        var solicitud = posible.get();
        if (!solicitud.token().equals(evento.token())) return rechazarConfirmacion(evento, "TOKEN_INVALIDO");
        if (!solicitud.corresponde(evento)) return rechazarConfirmacion(evento, "RESERVA_NO_COINCIDE");
        if (solicitud.asientoId() == null) {
            return rechazarConfirmacion(evento, solicitud.estado().equals("CANCELADA") ? "RESERVA_CANCELADA" : "RETENCION_INEXISTENTE");
        }
        if (!solicitud.asientoId().equals(evento.asientoId())) return rechazarConfirmacion(evento, "RESERVA_NO_COINCIDE");
        var posibleAsiento = asientos.findById(evento.asientoId());
        if (posibleAsiento.isEmpty()) return rechazarConfirmacion(evento, "ASIENTO_INEXISTENTE");
        Asiento asiento = posibleAsiento.get();
        if (!asiento.getVueloId().equals(evento.vueloId()) || !asiento.getNumeroAsiento().equals(evento.asiento())) {
            return rechazarConfirmacion(evento, "RESERVA_NO_COINCIDE");
        }
        var posibleRetencion = retenciones.findById(evento.token());
        if (posibleRetencion.isEmpty()) return rechazarConfirmacion(evento, "RETENCION_INEXISTENTE");
        Retencion retencion = posibleRetencion.get();
        if (!retencion.getAsientoId().equals(evento.asientoId())) return rechazarConfirmacion(evento, "RESERVA_NO_COINCIDE");
        boolean propietarioActual = evento.token().equals(asiento.getBloqueoToken());
        if (retencion.getEstado() == EstadoRetencion.CONFIRMADA) {
            if (asiento.getEstado() != EstadoAsiento.RESERVADO || !propietarioActual) {
                return rechazarConfirmacion(evento, "ASIENTO_NO_DISPONIBLE");
            }
            resultados.registrar(evento, "asiento.confirmado", asiento.getId(), null);
            return "YA_CONFIRMADA";
        }
        if (retencion.getEstado() == EstadoRetencion.EXPIRADA) return rechazarConfirmacion(evento, "RETENCION_EXPIRADA");
        if (solicitud.estado().equals("CANCELADA")) return rechazarConfirmacion(evento, "RESERVA_CANCELADA");
        if (retencion.getEstado() != EstadoRetencion.ACTIVA) return rechazarConfirmacion(evento, "RETENCION_TERMINAL");
        if (!retencion.getExpiraEn().isAfter(inventario.ahora())) {
            if (asiento.getEstado() == EstadoAsiento.BLOQUEADO && propietarioActual) inventario.expirarUno(asiento.getId());
            return rechazarConfirmacion(evento, "RETENCION_EXPIRADA");
        }
        if (asiento.getEstado() != EstadoAsiento.BLOQUEADO || !propietarioActual) {
            return rechazarConfirmacion(evento, "ASIENTO_NO_DISPONIBLE");
        }
        inventario.confirmar(asiento.getId(), evento.token());
        resultados.registrar(evento, "asiento.confirmado", asiento.getId(), null);
        return "ASIENTO_CONFIRMADO";
    }

    private String rechazarConfirmacion(EventoReserva evento, String motivo) {
        resultados.registrar(evento, "asiento.confirmacion_rechazada", evento.asientoId(), motivo);
        return motivo;
    }

    private String motivoBloqueo(String resultado) {
        return switch (resultado) {
            case "ASIENTO_INEXISTENTE", "TOKEN_DE_OTRA_RESERVA", "RETENCION_TERMINAL", "SOLICITUD_INVALIDA" -> resultado;
            case "ASIENTO_NO_DISPONIBLE" -> "ASIENTO_OCUPADO";
            case "IDENTIDAD_INCOHERENTE" -> "RESERVA_NO_COINCIDE";
            case "TOKEN_PREEXISTENTE_SIN_ASOCIACION" -> "TOKEN_INVALIDO";
            case "RETENCION_AUSENTE" -> "RETENCION_INEXISTENTE";
            case "RESERVA_YA_CANCELADA" -> "RESERVA_CANCELADA";
            case "SOLICITUD_YA_RECHAZADA" -> "SOLICITUD_RECHAZADA";
            default -> throw new IllegalStateException("Resultado de bloqueo no reconocido");
        };
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
            // No alterar las huellas ya guardadas de mensajes anteriores sin asientoId.
            if (evento.asientoId() != null) json.put("asientoId", evento.asientoId());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(json)));
        } catch (java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("No se pudo calcular identidad del mensaje", exception);
        }
    }
}

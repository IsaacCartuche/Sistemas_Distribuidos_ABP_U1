package com.empresa.inventario.outbox;

import com.empresa.inventario.domain.Asiento;
import com.empresa.inventario.domain.Retencion;
import com.empresa.inventario.inbox.SolicitudReservaRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

@Service
public class ExpiracionOutbox {
    private final OutboxRepository repository;
    private final ObjectMapper mapper;
    private final Clock reloj;
    private final SolicitudReservaRepository solicitudes;
    private final ValidadorExpiracion validador;
    public ExpiracionOutbox(OutboxRepository repository, ObjectMapper mapper, Clock reloj,
                            SolicitudReservaRepository solicitudes, ValidadorExpiracion validador) {
        this.repository = repository;
        this.mapper = mapper;
        this.reloj = reloj;
        this.solicitudes = solicitudes;
        this.validador = validador;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(Asiento asiento, Retencion retencion) {
        UUID eventId = UUID.randomUUID();
        ObjectNode evento = mapper.createObjectNode();
        evento.put("eventId", eventId.toString());
        // Ausentes de verdad: se conserva el evento, pero NO se envía sin contexto.
        evento.putNull("correlationId");
        evento.put("timestamp", reloj.instant().toString());
        evento.put("tipo", "asiento.expirado");
        ObjectNode payload = evento.putObject("payload");
        payload.putNull("reservaId");
        payload.put("vueloId", asiento.getVueloId());
        payload.put("asientoId", asiento.getId());
        payload.put("asiento", asiento.getNumeroAsiento());
        payload.put("token", retencion.getToken().toString());
        try {
            UUID correlationId = null;
            var contexto = solicitudes.contexto(retencion.getToken(), asiento.getId());
            if (contexto.isPresent()) {
                var solicitud = contexto.get();
                if (solicitud.vueloId() != asiento.getVueloId() || !solicitud.asiento().equals(asiento.getNumeroAsiento())) {
                    throw new IllegalStateException("Contexto de reserva incoherente con el asiento");
                }
                correlationId = solicitud.correlationId();
                evento.put("correlationId", correlationId.toString());
                payload.set("reservaId", mapper.readTree(solicitud.reservaJson()));
            }
            String mensaje = mapper.writeValueAsString(evento);
            if (correlationId != null) {
                validador.validar(new OutboxRepository.Evento(eventId, correlationId, retencion.getToken(), mensaje, 0));
            }
            repository.registrar(eventId, retencion.getToken(), mensaje, correlationId);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("No se pudo serializar la expiración", exception);
        }
    }
}

package com.empresa.inventario.outbox;

import com.empresa.inventario.domain.Asiento;
import com.empresa.inventario.domain.Retencion;
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
    public ExpiracionOutbox(OutboxRepository repository, ObjectMapper mapper, Clock reloj) {
        this.repository = repository;
        this.mapper = mapper;
        this.reloj = reloj;
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
            repository.registrar(eventId, retencion.getToken(), mapper.writeValueAsString(evento));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("No se pudo serializar la expiración", exception);
        }
    }
}

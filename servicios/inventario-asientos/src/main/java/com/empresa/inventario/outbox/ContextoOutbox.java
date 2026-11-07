package com.empresa.inventario.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Punto interno para la futura integración autorizada. No expone un endpoint ni inventa contexto. */
@Service
public class ContextoOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ValidadorExpiracion validador;
    public ContextoOutbox(JdbcTemplate jdbc, ObjectMapper mapper, ValidadorExpiracion validador) {
        this.jdbc = jdbc; this.mapper = mapper; this.validador = validador;
    }

    // JsonNode conserva el tipo original de reservaId: su tipo definitivo debe acordarse con Sebastián.
    @Transactional
    public boolean completar(UUID eventId, JsonNode reservaId, UUID correlationId) {
        if (eventId == null || correlationId == null || !ValidadorExpiracion.reservaValida(reservaId)) {
            throw new IllegalArgumentException("Se requieren identificadores reales de evento, reserva y correlación");
        }
        var mensajes = jdbc.query("""
                select mensaje, token, correlation_id from inventario_outbox
                where event_id = ? and estado = 'ESPERA_CONTEXTO'
                for update
                """, (rs, n) -> new OutboxRepository.Evento(eventId, rs.getObject("correlation_id", UUID.class),
                rs.getObject("token", UUID.class), rs.getString("mensaje"), 0), eventId);
        if (mensajes.isEmpty()) return false;
        try {
            OutboxRepository.Evento fila = mensajes.get(0);
            ObjectNode evento = validador.validarEsperaContexto(fila);
            evento.put("correlationId", correlationId.toString());
            ((ObjectNode) evento.get("payload")).set("reservaId", reservaId);
            String mensaje = mapper.writeValueAsString(evento);
            validador.validar(new OutboxRepository.Evento(eventId, correlationId, fila.token(), mensaje, 0));
            jdbc.update("""
                    update inventario_outbox set mensaje = ?, correlation_id = ?, estado = 'PENDIENTE',
                    siguiente_intento = CURRENT_TIMESTAMP where event_id = ?
                    """, mensaje, correlationId, eventId);
            return true;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Mensaje outbox inválido", exception);
        }
    }
}

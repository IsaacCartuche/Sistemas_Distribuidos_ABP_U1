package com.empresa.inventario.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

/** JDBC participa en la misma conexión/transacción que JPA, sin otra base de datos. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class OutboxRepository {
    private final JdbcTemplate jdbc;
    public OutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Evento(UUID eventId, UUID correlationId, UUID token, String mensaje, int intentos, String tipo) {
        public Evento(UUID eventId, UUID correlationId, UUID token, String mensaje, int intentos) {
            this(eventId, correlationId, token, mensaje, intentos, "asiento.expirado");
        }
    }

    public void registrar(UUID eventId, UUID token, String mensaje) {
        registrar(eventId, token, mensaje, null);
    }

    public void registrar(UUID eventId, UUID token, String mensaje, UUID correlationId) {
        registrar(eventId, token, mensaje, correlationId, "asiento.expirado", null);
    }

    public void registrar(UUID eventId, UUID token, String mensaje, UUID correlationId, String tipo, String claveNegocio) {
        boolean porRetencion = tipo.equals("asiento.expirado") || tipo.equals("asiento.bloqueado") || tipo.equals("asiento.confirmado");
        String conflicto = porRetencion
                ? "on conflict (tipo, token) where tipo in ('asiento.expirado', 'asiento.bloqueado', 'asiento.confirmado') do nothing"
                : "on conflict (tipo, clave_negocio) where clave_negocio is not null do nothing";
        jdbc.update("""
                insert into inventario_outbox(event_id, token, tipo, mensaje, correlation_id, estado, clave_negocio)
                values (?, ?, ?, ?, ?, ?, ?)
                """ + conflicto, eventId, token, tipo, mensaje, correlationId,
                correlationId == null ? "ESPERA_CONTEXTO" : "PENDIENTE", claveNegocio);
    }

    public Optional<Evento> reclamarPendiente() {
        return jdbc.query("""
                select event_id, correlation_id, token, mensaje, intentos, tipo from inventario_outbox
                where estado = 'PENDIENTE' and siguiente_intento <= CURRENT_TIMESTAMP
                order by siguiente_intento, creado_en, event_id
                limit 1 for update skip locked
                """, (rs, n) -> new Evento(rs.getObject("event_id", UUID.class),
                rs.getObject("correlation_id", UUID.class), rs.getObject("token", UUID.class),
                rs.getString("mensaje"), rs.getInt("intentos"), rs.getString("tipo")))
                .stream().findFirst();
    }

    public void enviado(UUID eventId) {
        jdbc.update("""
                update inventario_outbox set estado = 'ENVIADO', enviado_en = CURRENT_TIMESTAMP,
                intentos = intentos + 1, ultimo_error = null where event_id = ?
                """, eventId);
    }

    public void reintentar(UUID eventId, long esperaSegundos, String motivo) {
        jdbc.update("""
                update inventario_outbox set intentos = intentos + 1, ultimo_error = ?,
                siguiente_intento = CURRENT_TIMESTAMP + (? * interval '1 second') where event_id = ?
                """, motivo, esperaSegundos, eventId);
    }

    public void errorDatos(UUID eventId, String motivo) {
        jdbc.update("""
                update inventario_outbox set estado = 'ERROR_DATOS', intentos = intentos + 1,
                ultimo_error = ? where event_id = ?
                """, motivo, eventId);
    }
}

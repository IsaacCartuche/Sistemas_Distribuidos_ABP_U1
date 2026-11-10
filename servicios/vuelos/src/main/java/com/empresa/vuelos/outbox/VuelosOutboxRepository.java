package com.empresa.vuelos.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class VuelosOutboxRepository {
    private final JdbcTemplate jdbc;
    public VuelosOutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Evento(UUID eventId, UUID correlationId, Integer vueloId, Integer revision, String mensaje, int intentos) { }

    public void registrar(Evento evento) {
        jdbc.update("""
                insert into vuelos_outbox(event_id, correlation_id, vuelo_id, revision, tipo, mensaje)
                values (?, ?, ?, ?, 'vuelos.actualizados', ?)
                """, evento.eventId(), evento.correlationId(), evento.vueloId(), evento.revision(), evento.mensaje());
    }
    public Optional<Evento> reclamar() {
        return jdbc.query("""
                select event_id, correlation_id, vuelo_id, revision, mensaje, intentos
                from vuelos_outbox where estado = 'PENDIENTE' and siguiente_intento <= CURRENT_TIMESTAMP
                order by siguiente_intento, creado_en, event_id limit 1 for update skip locked
                """, (rs, n) -> new Evento(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getInt(6))).stream().findFirst();
    }
    public void enviado(UUID id) {
        jdbc.update("""
                update vuelos_outbox set estado = 'ENVIADO', enviado_en = CURRENT_TIMESTAMP,
                intentos = intentos + 1, ultimo_error = null where event_id = ?
                """, id);
    }
    public void reintentar(UUID id, long segundos, String motivo) {
        jdbc.update("""
                update vuelos_outbox set intentos = intentos + 1, ultimo_error = ?,
                siguiente_intento = CURRENT_TIMESTAMP + (? * interval '1 second') where event_id = ?
                """, motivo, segundos, id);
    }
    public void errorDatos(UUID id, String motivo) {
        jdbc.update("update vuelos_outbox set estado = 'ERROR_DATOS', intentos = intentos + 1, ultimo_error = ? where event_id = ?",
                motivo, id);
    }
}

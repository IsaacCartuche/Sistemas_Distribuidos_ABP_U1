package com.empresa.inventario.inbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

/** Asociación inmutable de identidades y marca durable para cancelaciones anticipadas. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class SolicitudReservaRepository {
    private final JdbcTemplate jdbc;
    public SolicitudReservaRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Solicitud(String reservaJson, UUID correlationId, UUID token, int vueloId,
                            String asiento, Integer asientoId, String estado) {
        public boolean corresponde(EventoReserva evento) {
            return reservaJson.equals(evento.claveReserva()) && correlationId.equals(evento.correlationId())
                    && token.equals(evento.token()) && vueloId == evento.vueloId() && asiento.equals(evento.asiento());
        }
    }
    public boolean insertar(EventoReserva evento) {
        return jdbc.update("""
                insert into inventario_solicitudes(reserva_clave, correlation_id, token, vuelo_id, numero_asiento, estado)
                values (?, ?, ?, ?, ?, 'RECIBIDA') on conflict do nothing
                """, evento.claveReserva(), evento.correlationId(), evento.token(), evento.vueloId(), evento.asiento()) == 1;
    }
    public Optional<Solicitud> bloquearReserva(String clave) {
        return jdbc.query("""
                select reserva_clave, correlation_id, token, vuelo_id, numero_asiento, asiento_id, estado
                from inventario_solicitudes where reserva_clave = ? for update
                """, (rs, n) -> new Solicitud(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getInt(4), rs.getString(5), rs.getObject(6, Integer.class), rs.getString(7)), clave).stream().findFirst();
    }
    public void estado(String clave, String estado, Integer asientoId) {
        jdbc.update("update inventario_solicitudes set estado = ?, asiento_id = ? where reserva_clave = ?",
                estado, asientoId, clave);
    }
    public Optional<Solicitud> contexto(UUID token, Integer asientoId) {
        return jdbc.query("""
                select reserva_clave, correlation_id, token, vuelo_id, numero_asiento, asiento_id, estado
                from inventario_solicitudes where token = ? and asiento_id = ? and estado in ('BLOQUEADA', 'CANCELADA')
                """, (rs, n) -> new Solicitud(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getInt(4), rs.getString(5), rs.getObject(6, Integer.class), rs.getString(7)), token, asientoId)
                .stream().findFirst();
    }
}

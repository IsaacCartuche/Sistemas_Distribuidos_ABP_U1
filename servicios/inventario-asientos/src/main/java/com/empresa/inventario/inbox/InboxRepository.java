package com.empresa.inventario.inbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class InboxRepository {
    public static final String CONSUMIDOR = "inventario.reservas.v1";
    private final JdbcTemplate jdbc;
    public InboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Procesado(String huella, String resultado) { }

    public boolean registrar(UUID eventId, String tipo, String huella) {
        return jdbc.update("""
                insert into inventario_inbox(consumidor, event_id, tipo, huella)
                values (?, ?, ?, ?) on conflict (consumidor, event_id) do nothing
                """, CONSUMIDOR, eventId, tipo, huella) == 1;
    }
    public Procesado obtener(UUID eventId) {
        return jdbc.queryForObject("""
                select huella, resultado from inventario_inbox where consumidor = ? and event_id = ?
                """, (rs, n) -> new Procesado(rs.getString(1), rs.getString(2)), CONSUMIDOR, eventId);
    }
    public void finalizar(UUID eventId, String resultado) {
        jdbc.update("""
                update inventario_inbox set resultado = ?, procesado_en = CURRENT_TIMESTAMP
                where consumidor = ? and event_id = ?
                """, resultado, CONSUMIDOR, eventId);
    }
}

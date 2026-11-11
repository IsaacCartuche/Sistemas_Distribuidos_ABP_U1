package com.empresa.reservas.saga;

import com.empresa.reservas.entity.OutboxEvento;
import com.empresa.reservas.entity.Reserva;
import com.empresa.reservas.repository.OutboxEventoRepository;
import com.empresa.reservas.repository.ReservaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class SagaService {
    private static final Set<String> TIPOS = Set.of(
            "asiento.bloqueado", "asiento.no_disponible", "asiento.expirado", "asiento.liberado",
            "pago.exitoso", "pago.fallido", "pago.reembolsado");
    private final ReservaRepository reservas;
    private final OutboxEventoRepository outbox;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SagaService(ReservaRepository reservas, OutboxEventoRepository outbox,
                       JdbcTemplate jdbc, ObjectMapper mapper) {
        this.reservas = reservas;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public void procesar(String tipo, EventoSaga evento) {
        evento.validar();
        if (!TIPOS.contains(tipo)) {
            throw new IllegalArgumentException("Evento no soportado");
        }
        Reserva reserva = reservas.bloquearPorCorrelationId(evento.correlationId())
                .orElseThrow(() -> new IllegalStateException("Reserva aun no disponible"));
        if (!reserva.getId().equals(evento.payload().path("reservaId").asInt())) {
            throw new IllegalArgumentException("Reserva y correlacion no coinciden");
        }
        // Un bloqueo de otro intento de inventario no prueba la precondicion actual.
        if (tipo.startsWith("asiento.") && (reserva.getToken() == null
                || !Objects.equals(reserva.getToken().toString(), evento.payload().path("token").asText()))) {
            throw new IllegalArgumentException("Token de asiento invalido");
        }
        if (jdbc.update("""
                insert into saga_inbox(event_id,tipo,correlation_id)
                values (?,?,?) on conflict do nothing
                """, evento.eventId(), tipo, evento.correlationId()) == 0) {
            return;
        }
        if ("CANCELADA".equals(reserva.getEstado())) {
            return;
        }
        if ("asiento.bloqueado".equals(tipo)) {
            reserva.setAsientoBloqueado(true);
        } else if ("pago.exitoso".equals(tipo)) {
            reserva.setPagoExitoso(true);
        } else {
            // Una perdida tardia del asiento exige compensar incluso tras confirmar.
            reserva.setEstado("CANCELADA");
            publicar(reserva, "reserva.cancelada");
            return;
        }
        if ("PENDIENTE".equals(reserva.getEstado())
                && reserva.isAsientoBloqueado() && reserva.isPagoExitoso()) {
            reserva.setEstado("CONFIRMADA");
            publicar(reserva, "reserva.confirmada");
        }
    }

    private void publicar(Reserva reserva, String tipo) {
        try {
            UUID id = UUID.randomUUID();
            var evento = new OutboxEvento();
            evento.setId(id);
            evento.setCorrelationId(reserva.getCorrelationId());
            evento.setTipo(tipo);
            evento.setExchange("vuelos.events");
            evento.setRoutingKey(tipo);
            evento.setPayload(mapper.writeValueAsString(Map.of(
                    "eventId", id, "correlationId", reserva.getCorrelationId(),
                    "timestamp", Instant.now().toString(), "payload", Map.of(
                            "reservaId", reserva.getId(), "vueloId", reserva.getVueloId(),
                            "asiento", reserva.getAsiento(), "token", reserva.getToken()))));
            outbox.saveAndFlush(evento);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar el estado de reserva", ex);
        }
    }
}

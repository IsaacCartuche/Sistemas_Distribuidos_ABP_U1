package com.empresa.pagos.saga;

import com.empresa.pagos.entity.OutboxEvento;
import com.empresa.pagos.repository.OutboxEventoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SagaService {
    private static final Set<String> TIPOS = Set.of(
            "reserva.creada", "asiento.bloqueado", "reserva.cancelada");
    private final JdbcTemplate jdbc;
    private final OutboxEventoRepository outbox;
    private final ObjectMapper mapper;
    private final PasarelaSimulada pasarela;

    public SagaService(JdbcTemplate jdbc, OutboxEventoRepository outbox,
                       ObjectMapper mapper, PasarelaSimulada pasarela) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.mapper = mapper;
        this.pasarela = pasarela;
    }

    @Transactional
    public void procesar(String tipo, EventoSaga evento) {
        evento.validar();
        if (!TIPOS.contains(tipo)) {
            throw new IllegalArgumentException("Evento no soportado");
        }
        UUID token;
        try {
            token = UUID.fromString(evento.payload().path("token").asText());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Token requerido", ex);
        }
        int reserva = evento.payload().path("reservaId").asInt();

        // La fila puede nacer como cancelacion anticipada: nunca se cobrara despues.
        // ON CONFLICT y FOR UPDATE serializan tambien la primera entrega concurrente.
        jdbc.update("""
                insert into pago_saga(correlation_id,reserva_id,token,estado)
                values (?,?,?,'PENDIENTE') on conflict (correlation_id) do nothing
                """, evento.correlationId(), reserva, token);
        var fila = jdbc.queryForMap(
                "select * from pago_saga where correlation_id=? for update", evento.correlationId());
        if (((Number) fila.get("reserva_id")).intValue() != reserva) {
            throw new IllegalArgumentException("Correlacion incompatible");
        }
        if (!token.equals(fila.get("token"))) {
            throw new IllegalArgumentException("Token incompatible");
        }

        BigDecimal monto = (BigDecimal) fila.get("monto");
        if (tipo.equals("reserva.creada")) {
            BigDecimal solicitado = validarMonto(evento.payload().get("total"));
            if (monto != null && monto.compareTo(solicitado) != 0) {
                throw new IllegalArgumentException("Importe incompatible");
            }
            monto = solicitado;
        }
        if (jdbc.update("""
                insert into saga_inbox(event_id,tipo,correlation_id)
                values (?,?,?) on conflict do nothing
                """, evento.eventId(), tipo, evento.correlationId()) == 0) {
            return;
        }

        boolean creado = (boolean) fila.get("creado") || tipo.equals("reserva.creada");
        boolean bloqueado = (boolean) fila.get("bloqueado") || tipo.equals("asiento.bloqueado");
        boolean cancelado = (boolean) fila.get("cancelado") || tipo.equals("reserva.cancelada");
        String estado = (String) fila.get("estado");
        String publicado = null;

        // Este adaptador es una funcion local sin I/O. Una pasarela real necesitara
        // un worker de operaciones persistidas con claves idempotentes externas.
        if (cancelado && estado.equals("EXITOSO")) {
            pasarela.reembolsar(evento.correlationId());
            estado = "REEMBOLSADO";
            publicado = "pago.reembolsado";
        } else if (cancelado && estado.equals("PENDIENTE")) {
            estado = "CANCELADO";
        } else if (!cancelado && creado && bloqueado && estado.equals("PENDIENTE")) {
            boolean exitoso = pasarela.cobrar(evento.correlationId(), monto);
            estado = exitoso ? "EXITOSO" : "FALLIDO";
            publicado = exitoso ? "pago.exitoso" : "pago.fallido";
        }

        jdbc.update("""
                update pago_saga set monto=?,creado=?,bloqueado=?,cancelado=?,estado=?,version=version+1
                where correlation_id=?
                """, monto, creado, bloqueado, cancelado, estado, evento.correlationId());
        if (publicado != null) {
            jdbc.update("""
                    insert into pago_intentos(correlation_id,operacion,resultado) values (?,?,?)
                    """, evento.correlationId(), publicado.equals("pago.reembolsado") ? "REEMBOLSO" : "COBRO", estado);
            publicar(evento, reserva, monto, publicado);
        }
    }

    private BigDecimal validarMonto(JsonNode nodo) {
        if (nodo == null || !nodo.isNumber() || nodo.decimalValue().signum() <= 0
                || nodo.decimalValue().scale() > 2
                || nodo.decimalValue().compareTo(new BigDecimal("99999999.99")) > 0) {
            throw new IllegalArgumentException("total positivo DECIMAL(10,2) requerido; contrato pendiente");
        }
        return nodo.decimalValue();
    }

    private void publicar(EventoSaga source, int reserva, BigDecimal monto, String tipo) {
        try {
            UUID id = UUID.randomUUID();
            var evento = new OutboxEvento();
            evento.setId(id);
            evento.setCorrelationId(source.correlationId());
            evento.setTipo(tipo);
            evento.setExchange("vuelos.events");
            evento.setRoutingKey(tipo);
            evento.setPayload(mapper.writeValueAsString(Map.of(
                    "eventId", id, "correlationId", source.correlationId(),
                    "timestamp", Instant.now().toString(),
                    "payload", Map.of("reservaId", reserva, "total", monto, "simulado", true))));
            outbox.saveAndFlush(evento);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar el evento de pago", ex);
        }
    }
}

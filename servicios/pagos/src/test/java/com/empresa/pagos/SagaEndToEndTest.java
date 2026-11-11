package com.empresa.pagos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Requiere Reservas ejecutándose contra infraestructura EXCLUSIVA de pruebas. */
@EnabledIfEnvironmentVariable(named = "SAGA_E2E", matches = "true")
@SpringBootTest(properties = {
        "saga.habilitada=${saga.e2e.consumidor:true}", "outbox.publicacion.habilitada=${saga.e2e.consumidor:true}",
        "outbox.publicacion.intervalo-ms=100", "outbox.publicacion.espera-inicial-ms=100",
        "outbox.recuperacion.habilitada=false"
})
class SagaEndToEndTest {
    @Autowired RabbitAdmin admin;
    @Autowired RabbitTemplate rabbit;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String base = System.getenv().getOrDefault("RESERVAS_TEST_URL", "http://127.0.0.1:18082");

    @Test
    void reservaPagoConfirmacionCancelacionYReembolso() throws Exception {
        String name = "saga.e2e." + UUID.randomUUID();
        var queue = new org.springframework.amqp.core.Queue(name, false, true, true);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("vuelos.events")).with("reserva.creada"));
        try {
            var request = HttpRequest.newBuilder(URI.create(base + "/api/reservas"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                            "vueloId", 1, "usuarioId", 1, "asiento", "2B", "total", 100,
                            "idempotencyKey", "e2e-" + UUID.randomUUID())))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, response.statusCode(), response.body());
            int reserva = mapper.readTree(response.body()).path("id").asInt();
            Message created = rabbit.receive(name, 15000);
            assertNotNull(created);
            JsonNode event = mapper.readTree(created.getBody());
            UUID correlation = UUID.fromString(event.path("correlationId").asText());
            var payload = Map.of("reservaId", reserva, "token", event.path("payload").path("token").asText());
            publicar("asiento.bloqueado", correlation, payload);
            await().atMost(Duration.ofSeconds(35)).untilAsserted(() -> {
                assertEquals("CONFIRMADA", estadoReserva(reserva));
                assertEquals("EXITOSO", estadoPago(correlation));
            });
            publicar("asiento.expirado", correlation, payload);
            await().atMost(Duration.ofSeconds(35)).untilAsserted(() -> {
                assertEquals("CANCELADA", estadoReserva(reserva));
                assertEquals("REEMBOLSADO", estadoPago(correlation));
                assertEquals(2, jdbc.queryForObject("select count(*) from pago_intentos where correlation_id=?", Integer.class, correlation));
                assertEquals(2, jdbc.queryForObject("select count(*) from outbox_eventos where correlation_id=? and estado='PUBLICADO'", Integer.class, correlation));
            });
        } finally {
            admin.deleteQueue(name);
        }
    }

    @Test
    void pagoFallidoCancelaYEmiteSolicitudDeLiberacion() throws Exception {
        String name = "saga.e2e.fallo." + UUID.randomUUID();
        var queue = new org.springframework.amqp.core.Queue(name, false, true, false);
        admin.declareQueue(queue);
        var exchange = new TopicExchange("vuelos.events");
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("reserva.creada"));
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("reserva.cancelada"));
        try {
            var request = HttpRequest.newBuilder(URI.create(base + "/api/reservas"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                            "vueloId", 1, "usuarioId", 1, "asiento", "5E", "total", 20000,
                            "idempotencyKey", "e2e-fallo-" + UUID.randomUUID())))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, response.statusCode(), response.body());
            int reserva = mapper.readTree(response.body()).path("id").asInt();
            Message created = rabbit.receive(name, 15000);
            assertNotNull(created);
            JsonNode event = mapper.readTree(created.getBody());
            UUID correlation = UUID.fromString(event.path("correlationId").asText());
            var payload = Map.of("reservaId", reserva, "token", event.path("payload").path("token").asText());
            publicar("asiento.bloqueado", correlation, payload);
            publicar("asiento.bloqueado", correlation, payload);
            await().atMost(Duration.ofSeconds(35)).untilAsserted(() -> {
                assertEquals("CANCELADA", estadoReserva(reserva));
                assertEquals("FALLIDO", estadoPago(correlation));
                assertEquals(1, jdbc.queryForObject("select count(*) from pago_intentos where correlation_id=?", Integer.class, correlation));
                assertEquals(1, jdbc.queryForObject("select count(*) from outbox_eventos where correlation_id=? and tipo='pago.fallido' and estado='PUBLICADO'", Integer.class, correlation));
            });
            Message cancelacion = rabbit.receive(name, 15000);
            assertNotNull(cancelacion);
            assertEquals("reserva.cancelada", cancelacion.getMessageProperties().getReceivedRoutingKey());
            JsonNode json = mapper.readTree(cancelacion.getBody());
            assertEquals(correlation.toString(), json.path("correlationId").asText());
            assertEquals(payload.get("token"), json.path("payload").path("token").asText());
        } finally {
            admin.deleteQueue(name);
        }
    }

    private void publicar(String tipo, UUID correlation, Map<String, ?> payload) throws Exception {
        var props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbit.send("vuelos.events", tipo, new Message(mapper.writeValueAsBytes(Map.of(
                "eventId", UUID.randomUUID(), "correlationId", correlation,
                "timestamp", java.time.Instant.now().toString(), "payload", payload)), props));
    }

    private String estadoPago(UUID correlation) {
        return jdbc.queryForObject("select estado from pago_saga where correlation_id=?", String.class, correlation);
    }

    private String estadoReserva(int id) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/reservas/" + id)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return mapper.readTree(response.body()).path("estado").asText();
    }
}

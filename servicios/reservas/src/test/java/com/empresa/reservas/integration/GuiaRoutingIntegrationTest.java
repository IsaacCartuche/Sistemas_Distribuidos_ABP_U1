package com.empresa.reservas.integration;

import com.empresa.reservas.dto.CrearReservaRequest;
import com.empresa.reservas.entity.Reserva;
import com.empresa.reservas.repository.ReservaRepository;
import com.empresa.reservas.service.OutboxProcesamientoService;
import com.empresa.reservas.service.ReservaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Las colas externas se provisionan SOLO como dobles de infraestructura en pruebas aisladas. */
@SpringBootTest(properties={"saga.habilitada=true", "saga.topologia=guia",
        "outbox.publicacion.habilitada=false", "outbox.recuperacion.habilitada=false"})
@Import(GuiaRoutingIntegrationTest.InfraestructuraPrueba.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class GuiaRoutingIntegrationTest {
    @TestConfiguration
    static class InfraestructuraPrueba {
        @Bean Declarables colasExternasDePrueba() {
            var pagos = QueueBuilder.durable("pagos.media").maxPriority(5)
                    .deadLetterExchange("dlq.reservas").deadLetterRoutingKey("reservas.saga").build();
            var reservas = QueueBuilder.durable("reservas.alta").maxPriority(10)
                    .deadLetterExchange("dlq.reservas").deadLetterRoutingKey("reservas.saga").build();
            var notificaciones = QueueBuilder.durable("notificaciones.entrada").build();
            var inventario = QueueBuilder.durable("inventario.entrada").build();
            var exchange = new TopicExchange("vuelos.events");
            return new Declarables(pagos, reservas, notificaciones, inventario,
                    BindingBuilder.bind(notificaciones).to(exchange).with("reserva.*"),
                    BindingBuilder.bind(inventario).to(exchange).with("reserva.creada"),
                    BindingBuilder.bind(inventario).to(exchange).with("reserva.cancelada"));
        }
    }

    @Autowired ReservaService service;
    @Autowired ReservaRepository reservas;
    @Autowired OutboxProcesamientoService outbox;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    Reserva reserva;

    @BeforeEach void preparar() {
        admin.purgeQueue("notificaciones.entrada", false);
        admin.purgeQueue("inventario.entrada", false);
        reserva=service.crearReserva(new CrearReservaRequest(1,1,"4D",new BigDecimal("100.00"),"guia-"+UUID.randomUUID()));
    }

    @AfterEach void limpiarSoloCorrelacionPropia() {
        tx.executeWithoutResult(s -> {
            jdbc.update("delete from saga_inbox where correlation_id=?", reserva.getCorrelationId());
            jdbc.update("delete from outbox_eventos where correlation_id=?", reserva.getCorrelationId());
            reservas.deleteById(reserva.getId());
        });
    }

    @Test void pagoDesdePagosMediaExigeBloqueoYPublicaATodosLosDestinos() throws Exception {
        outbox.procesarPendientes();
        JsonNode creada=recibir("notificaciones.entrada");
        assertEquals(creada.path("eventId"),recibir("inventario.entrada").path("eventId"));
        assertEquals(0,new BigDecimal("100").compareTo(creada.path("payload").path("total").decimalValue()));
        byte[] pago=evento();
        enviar("pago.exitoso",pago); enviar("pago.exitoso",pago);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(1,inbox()));
        assertEquals("PENDIENTE",estado());
        enviar("asiento.bloqueado",evento());
        esperar("CONFIRMADA");
        outbox.procesarPendientes();
        assertEquals(reserva.getId().intValue(),recibir("notificaciones.entrada").path("payload").path("reservaId").asInt());
        assertNull(rabbit.receive("inventario.entrada"));
        assertEquals(1,contar("reserva.confirmada"));
    }

    @Test void falloDuplicadoCancelaYCompensaSinAfirmarLiberacion() throws Exception {
        outbox.procesarPendientes(); recibir("notificaciones.entrada"); recibir("inventario.entrada");
        enviar("asiento.bloqueado",evento());
        byte[] fallo=evento(); enviar("pago.fallido",fallo); enviar("pago.fallido",fallo);
        esperar("CANCELADA");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(2,inbox()));
        outbox.procesarPendientes();
        JsonNode cancelada=recibir("notificaciones.entrada");
        assertEquals(cancelada.path("eventId"),recibir("inventario.entrada").path("eventId"));
        assertEquals(reserva.getToken().toString(),cancelada.path("payload").path("token").asText());
        assertEquals(1,contar("reserva.cancelada"));
        assertEquals(0,contar("asiento.liberado"));
    }

    @Test void expiracionDesdeReservasAltaEsTerminalAnteExitosTardios() throws Exception {
        byte[] expiracion=evento(); enviar("asiento.expirado",expiracion); enviar("asiento.expirado",expiracion);
        esperar("CANCELADA");
        enviar("pago.exitoso",evento()); enviar("asiento.bloqueado",evento());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(3,inbox()));
        assertEquals("CANCELADA",estado()); assertEquals(1,contar("reserva.cancelada")); assertEquals(0,contar("reserva.confirmada"));
    }

    @Test void mensajeInvalidoEnColaGuiaLlegaADlq() {
        admin.purgeQueue("reservas.saga.dlq",false);
        var props=new MessageProperties(); String id=UUID.randomUUID().toString(); props.setMessageId(id);
        rabbit.send("", "pagos.media",new Message("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),props));
        Message dead=rabbit.receive("reservas.saga.dlq",10000);
        assertNotNull(dead); assertEquals(id,dead.getMessageProperties().getMessageId());
        assertNotNull(dead.getMessageProperties().getHeaders().get("x-death"));
    }

    byte[] evento() throws Exception {
        return mapper.writeValueAsBytes(Map.of("eventId",UUID.randomUUID(),"correlationId",reserva.getCorrelationId(),
                "timestamp",java.time.Instant.now().toString(),"payload",Map.of("reservaId",reserva.getId(),"token",reserva.getToken())));
    }
    void enviar(String tipo, byte[] body) {rabbit.send("vuelos.events",tipo,new Message(body,new MessageProperties()));}
    String estado() {return reservas.findById(reserva.getId()).orElseThrow().getEstado();}
    void esperar(String estado) {await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(estado,estado()));}
    int inbox() {return jdbc.queryForObject("select count(*) from saga_inbox where correlation_id=?",Integer.class,reserva.getCorrelationId());}
    int contar(String tipo) {return jdbc.queryForObject("select count(*) from outbox_eventos where correlation_id=? and tipo=?",Integer.class,reserva.getCorrelationId(),tipo);}
    JsonNode recibir(String cola) throws Exception {
        Message mensaje=rabbit.receive(cola,5000); assertNotNull(mensaje);
        JsonNode json=mapper.readTree(mensaje.getBody());
        assertEquals(reserva.getCorrelationId().toString(),json.path("correlationId").asText());
        assertEquals(MessageDeliveryMode.PERSISTENT,mensaje.getMessageProperties().getReceivedDeliveryMode());
        return json;
    }
}

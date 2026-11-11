package com.empresa.pagos;

import com.empresa.pagos.saga.*;
import com.empresa.pagos.service.OutboxProcesamientoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"saga.habilitada=false","outbox.publicacion.habilitada=false","outbox.recuperacion.habilitada=false"})
class GuiaPublicacionIntegrationTest {
    @Autowired SagaService saga;
    @Autowired OutboxProcesamientoService outbox;
    @Autowired RabbitAdmin admin;
    @Autowired RabbitTemplate rabbit;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    UUID correlation;
    UUID token;
    int reserva;

    @BeforeEach void prepararDestinosSoloEnBrokerDePrueba() {
        correlation=UUID.randomUUID(); token=UUID.randomUUID(); reserva=Math.floorMod(correlation.hashCode(),2000000000)+1;
        var resultados=QueueBuilder.durable("pagos.media").maxPriority(5)
                .deadLetterExchange("dlq.reservas").deadLetterRoutingKey("reservas.saga").build();
        var notificaciones=QueueBuilder.durable("notificaciones.entrada").build();
        var exchange=new TopicExchange("vuelos.events"); admin.declareExchange(exchange);
        admin.declareQueue(resultados); admin.declareQueue(notificaciones);
        for(String key:new String[]{"pago.exitoso","pago.fallido","pago.reembolsado"}) {
            admin.declareBinding(BindingBuilder.bind(resultados).to(exchange).with(key));
            admin.declareBinding(BindingBuilder.bind(notificaciones).to(exchange).with(key));
        }
        admin.purgeQueue("pagos.media",false); admin.purgeQueue("notificaciones.entrada",false);
    }

    @AfterEach void limpiarSoloDatosPropios() {
        tx.executeWithoutResult(s -> {
            jdbc.update("delete from outbox_eventos where correlation_id=?",correlation);
            jdbc.update("delete from saga_inbox where correlation_id=?",correlation);
            jdbc.update("delete from pago_intentos where correlation_id=?",correlation);
            jdbc.update("delete from pago_saga where correlation_id=?",correlation);
        });
    }

    @Test void exitoYReembolsoSePublicanUnaVezConCorrelacion() throws Exception {
        saga.procesar("reserva.creada",evento("100")); saga.procesar("asiento.bloqueado",evento("100"));
        verificarPublicacion("pago.exitoso");
        EventoSaga cancelacion=evento("100");
        saga.procesar("reserva.cancelada",cancelacion); saga.procesar("reserva.cancelada",cancelacion);
        saga.procesar("reserva.cancelada",evento("100"));
        verificarPublicacion("pago.reembolsado");
        assertEquals(2,jdbc.queryForObject("select count(*) from pago_intentos where correlation_id=?",Integer.class,correlation));
    }

    @Test void falloSePublicaAPagosMediaYNotificaciones() throws Exception {
        saga.procesar("asiento.bloqueado",evento("20000")); saga.procesar("reserva.creada",evento("20000"));
        verificarPublicacion("pago.fallido");
    }

    @Test void noInventaImporteNiCobraCuandoFaltaTotal() {
        saga.procesar("asiento.bloqueado",evento("100"));
        var incompleto=new EventoSaga(UUID.randomUUID(),correlation,"2026-11-10T12:00:00Z",
                mapper.valueToTree(Map.of("reservaId",reserva,"token",token)));
        assertThrows(IllegalArgumentException.class,() -> saga.procesar("reserva.creada",incompleto));
        assertEquals(0,jdbc.queryForObject("select count(*) from pago_intentos where correlation_id=?",Integer.class,correlation));
        assertEquals("PENDIENTE",jdbc.queryForObject("select estado from pago_saga where correlation_id=?",String.class,correlation));
    }

    void verificarPublicacion(String tipo) throws Exception {
        outbox.procesarPendientes();
        Message resultado=rabbit.receive("pagos.media",5000);
        Message notificacion=rabbit.receive("notificaciones.entrada",5000);
        assertNotNull(resultado); assertNotNull(notificacion);
        assertEquals(tipo,resultado.getMessageProperties().getReceivedRoutingKey());
        assertEquals(tipo,notificacion.getMessageProperties().getReceivedRoutingKey());
        JsonNode json=mapper.readTree(resultado.getBody());
        assertEquals(json.path("eventId"),mapper.readTree(notificacion.getBody()).path("eventId"));
        assertEquals(correlation.toString(),json.path("correlationId").asText());
        assertEquals(reserva,json.path("payload").path("reservaId").asInt());
        assertEquals(MessageDeliveryMode.PERSISTENT,resultado.getMessageProperties().getReceivedDeliveryMode());
        assertEquals("PUBLICADO",jdbc.queryForObject("select estado from outbox_eventos where correlation_id=? and tipo=?",String.class,correlation,tipo));
    }

    EventoSaga evento(String monto) {
        return new EventoSaga(UUID.randomUUID(),correlation,"2026-11-10T12:00:00Z",
                mapper.valueToTree(Map.of("reservaId",reserva,"token",token,"total",new BigDecimal(monto))));
    }
}

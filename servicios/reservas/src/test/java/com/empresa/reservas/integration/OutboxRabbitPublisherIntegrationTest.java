package com.empresa.reservas.integration;

import com.empresa.reservas.entity.OutboxEvento;
import com.empresa.reservas.service.OutboxRabbitPublisher;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.nio.charset.StandardCharsets;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class OutboxRabbitPublisherIntegrationTest {

        private static final String COLA_PRUEBA = "reservas.test.outbox";

        @Autowired
        private OutboxRabbitPublisher publisher;

        @Autowired
        private RabbitTemplate rabbitTemplate;

        @Test
        void debePublicarEventoConfirmadoYEnrutado() {

                UUID eventoId = UUID.randomUUID();

                OutboxEvento evento = new OutboxEvento();
                evento.setId(eventoId);
                evento.setCorrelationId(UUID.randomUUID());
                evento.setTipo("reserva.creada");
                evento.setExchange("vuelos.events");
                evento.setRoutingKey("reserva.creada");
                evento.setPayload(
                                "{\"eventId\":\"" + eventoId + "\"}");

                assertTrue(
                                publisher.publicar(evento),
                                "RabbitMQ debe confirmar la publicación");

                Object recibido = rabbitTemplate.receiveAndConvert(
                                COLA_PRUEBA,
                                5000);

                assertNotNull(recibido,
                                "La cola debe recibir el mensaje");

                String contenido = recibido instanceof byte[]
                                ? new String((byte[]) recibido, StandardCharsets.UTF_8)
                                : recibido.toString();

                assertTrue(
                                contenido.contains(eventoId.toString()),
                                "El mensaje debe corresponder al evento publicado");
        }
}

package com.empresa.reservas.integration;

import com.empresa.reservas.dto.CrearReservaRequest;
import com.empresa.reservas.entity.Reserva;
import com.empresa.reservas.repository.OutboxEventoRepository;
import com.empresa.reservas.service.ReservaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ReservaOutboxIntegrationTest {

        @Autowired
        private ReservaService reservaService;

        @Autowired
        private OutboxEventoRepository outboxRepository;

        @Autowired
        private ObjectMapper objectMapper;

        @Test
        void crearReservaDebeGuardarEventoOutbox() throws Exception {

                String clave = "outbox-test-" + UUID.randomUUID();

                CrearReservaRequest request = new CrearReservaRequest(
                                15,
                                7,
                                "14C",
                                new BigDecimal("150.00"),
                                clave);

                Reserva reserva = reservaService.crearReserva(request);

                assertNotNull(reserva.getId());
                assertNotNull(reserva.getCorrelationId());

                var evento = outboxRepository
                                .findByCorrelationId(reserva.getCorrelationId())
                                .orElseThrow(() -> new AssertionError(
                                                "No se encontró el evento Outbox de la reserva"));

                assertEquals("reserva.creada", evento.getTipo());
                assertEquals("vuelos.events", evento.getExchange());
                assertEquals("reserva.creada", evento.getRoutingKey());
                assertEquals("PENDIENTE", evento.getEstado());

                JsonNode json = objectMapper.readTree(evento.getPayload());

                assertEquals(reserva.getId().intValue(),
                                json.path("payload").path("reservaId").asInt());

                assertEquals(reserva.getVueloId().intValue(),
                                json.path("payload").path("vueloId").asInt());

                assertEquals(reserva.getCorrelationId().toString(),
                                json.path("correlationId").asText());
        }

        @Test
        void solicitudRepetidaNoDebeCrearOtroEventoOutbox() {

                String clave = "outbox-idempotencia-" + UUID.randomUUID();

                CrearReservaRequest request = new CrearReservaRequest(
                                15,
                                7,
                                "14C",
                                new BigDecimal("150.00"),
                                clave);

                Reserva primera = reservaService.crearReserva(request);

                long eventosAntes = outboxRepository.count();

                Reserva segunda = reservaService.crearReserva(request);

                long eventosDespues = outboxRepository.count();

                assertEquals(primera.getId(), segunda.getId());
                assertEquals(eventosAntes, eventosDespues);
        }

}

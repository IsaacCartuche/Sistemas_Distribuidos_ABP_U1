package com.empresa.reservas.integration;

import com.empresa.reservas.dto.CrearReservaRequest;
import com.empresa.reservas.entity.Reserva;
import com.empresa.reservas.repository.OutboxEventoRepository;
import com.empresa.reservas.service.ReservaService;
import com.empresa.reservas.repository.ReservaRepository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

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

        @Autowired
        private ReservaRepository reservaRepository;

        @Autowired
        private TransactionTemplate transactionTemplate;

        private String clavePrueba;

        @AfterEach
        void limpiarDatosPrueba() {
                if (clavePrueba == null) {
                        return;
                }

                transactionTemplate.executeWithoutResult(status -> {
                        reservaRepository.findByIdempotencyKey(clavePrueba)
                                        .ifPresent(reserva -> {
                                                outboxRepository.findByCorrelationId(
                                                                reserva.getCorrelationId())
                                                                .ifPresent(outboxRepository::delete);

                                                reservaRepository.delete(reserva);
                                        });
                });
        }

        @Test
        void crearReservaDebeGuardarEventoOutbox() throws Exception {

                String clave = "outbox-test-" + UUID.randomUUID();
                clavePrueba = clave;

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
                clavePrueba = clave;

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

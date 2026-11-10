package com.empresa.reservas.integration;

import com.empresa.reservas.entity.OutboxEvento;
import com.empresa.reservas.repository.OutboxEventoRepository;
import com.empresa.reservas.service.OutboxReclamacionService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class OutboxReclamacionIntegrationTest {

    @Autowired
    private OutboxReclamacionService reclamacionService;

    @Autowired
    private OutboxEventoRepository repository;

    @Test
    @Transactional
    void reclamarEventosDebeActualizarSuEstado() {

        UUID instanciaId = UUID.randomUUID();
        OutboxEvento eventoPrueba = new OutboxEvento();

        eventoPrueba.setId(UUID.randomUUID());
        eventoPrueba.setCorrelationId(UUID.randomUUID());
        eventoPrueba.setTipo("reserva.creada");
        eventoPrueba.setExchange("vuelos.events");
        eventoPrueba.setRoutingKey("reserva.creada");
        eventoPrueba.setPayload("{\"tipo\":\"prueba\"}");
        eventoPrueba.setEstado("PENDIENTE");
        eventoPrueba.setFechaCreacion(LocalDateTime.now());
        eventoPrueba.setIntentos(0);

        repository.saveAndFlush(eventoPrueba);

        List<OutboxEvento> eventos = reclamacionService.reclamarPendientes(instanciaId);

        assertFalse(eventos.isEmpty(),
                "Deben existir eventos pendientes para probar");
        assertTrue(
                eventos.stream()
                        .anyMatch(e -> e.getId().equals(eventoPrueba.getId())),
                "El evento creado por la prueba debe ser reclamado");

        for (OutboxEvento evento : eventos) {

            assertEquals("EN_PROCESO", evento.getEstado());
            assertEquals(instanciaId, evento.getReclamadoPor());
            assertNotNull(evento.getFechaReclamo());
            assertTrue(evento.getIntentos() >= 1);
        }

        List<OutboxEvento> pendientes = repository.bloquearEventosPendientes("PENDIENTE");

        for (OutboxEvento evento : eventos) {
            assertTrue(
                    pendientes.stream()
                            .noneMatch(p -> p.getId().equals(evento.getId())));
        }
    }
}

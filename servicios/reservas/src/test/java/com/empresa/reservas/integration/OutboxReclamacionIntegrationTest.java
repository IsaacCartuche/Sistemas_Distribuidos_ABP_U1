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

        List<OutboxEvento> eventos =
                reclamacionService.reclamarPendientes(instanciaId);

        assertFalse(eventos.isEmpty(),
                "Deben existir eventos pendientes para probar");

        for (OutboxEvento evento : eventos) {

            assertEquals("EN_PROCESO", evento.getEstado());
            assertEquals(instanciaId, evento.getReclamadoPor());
            assertNotNull(evento.getFechaReclamo());
            assertTrue(evento.getIntentos() >= 1);
        }

        List<OutboxEvento> pendientes =
                repository.bloquearEventosPendientes("PENDIENTE");

        for (OutboxEvento evento : eventos) {
            assertTrue(
                    pendientes.stream()
                            .noneMatch(p -> p.getId().equals(evento.getId()))
            );
        }
    }
}

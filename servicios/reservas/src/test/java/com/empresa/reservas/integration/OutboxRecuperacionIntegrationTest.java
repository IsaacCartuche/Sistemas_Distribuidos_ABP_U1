
package com.empresa.reservas.integration;

import com.empresa.reservas.entity.OutboxEvento;
import com.empresa.reservas.repository.OutboxEventoRepository;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class OutboxRecuperacionIntegrationTest {

    @Autowired
    private OutboxEventoRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void debeRecuperarSoloEventosAbandonados() {

        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime limite = ahora.minusMinutes(5);

        // Evento abandonado hace 10 minutos
        OutboxEvento abandonado = crearEvento(
                "EN_PROCESO",
                ahora.minusMinutes(10)
        );

        // Evento reclamado recientemente
        OutboxEvento reciente = crearEvento(
                "EN_PROCESO",
                ahora.minusMinutes(1)
        );

        // Evento publicado anteriormente
        OutboxEvento publicado = crearEvento(
                "PUBLICADO",
                ahora.minusMinutes(10)
        );

        repository.saveAndFlush(abandonado);
        repository.saveAndFlush(reciente);
        repository.saveAndFlush(publicado);

        // Ejecutar recuperación
        int recuperados =
                repository.recuperarEventosAbandonados(limite);

        assertEquals(1, recuperados,
                "Solo debe recuperarse el evento abandonado");

        // Limpiar caché JPA para consultar los valores reales
        entityManager.clear();

        OutboxEvento eventoAbandonado =
                repository.findById(abandonado.getId()).orElseThrow();

        OutboxEvento eventoReciente =
                repository.findById(reciente.getId()).orElseThrow();

        OutboxEvento eventoPublicado =
                repository.findById(publicado.getId()).orElseThrow();

        // Comprobar recuperación
        assertEquals("PENDIENTE",
                eventoAbandonado.getEstado());

        assertNull(eventoAbandonado.getFechaReclamo());
        assertNull(eventoAbandonado.getReclamadoPor());

        // Comprobar que no se modifica el evento reciente
        assertEquals("EN_PROCESO",
                eventoReciente.getEstado());

        assertNotNull(eventoReciente.getFechaReclamo());
        assertNotNull(eventoReciente.getReclamadoPor());

        // Comprobar que no se modifica el publicado
        assertEquals("PUBLICADO",
                eventoPublicado.getEstado());
    }

    private OutboxEvento crearEvento(
            String estado,
            LocalDateTime fechaReclamo) {

        OutboxEvento evento = new OutboxEvento();

        evento.setId(UUID.randomUUID());
        evento.setCorrelationId(UUID.randomUUID());
        evento.setTipo("test.recuperacion");
        evento.setExchange("vuelos.events");
        evento.setRoutingKey("test.recuperacion");
        evento.setPayload("{}");

        evento.setEstado(estado);
        evento.setFechaReclamo(fechaReclamo);
        evento.setReclamadoPor(UUID.randomUUID());

        return evento;
    }
}

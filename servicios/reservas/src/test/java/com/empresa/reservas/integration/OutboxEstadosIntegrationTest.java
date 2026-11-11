
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
class OutboxEstadosIntegrationTest {

    @Autowired
    private OutboxEventoRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void debeMarcarPublicadoSoloSiPerteneceALaInstancia() {

        UUID instanciaId = UUID.randomUUID();
        UUID otraInstancia = UUID.randomUUID();

        OutboxEvento evento = crearEvento(instanciaId);
        repository.saveAndFlush(evento);

        // Una instancia diferente no puede confirmar el evento
        int incorrecto = repository.marcarPublicado(
                evento.getId(),
                otraInstancia,
                LocalDateTime.now()
        );

        assertEquals(0, incorrecto);

        // La instancia propietaria sí puede confirmarlo
        int correcto = repository.marcarPublicado(
                evento.getId(),
                instanciaId,
                LocalDateTime.now()
        );

        assertEquals(1, correcto);

        entityManager.clear();

        OutboxEvento actualizado = repository
                .findById(evento.getId())
                .orElseThrow();

        assertEquals("PUBLICADO", actualizado.getEstado());
        assertNotNull(actualizado.getFechaPublicacion());
        assertNull(actualizado.getFechaReclamo());
        assertNull(actualizado.getReclamadoPor());

        // No permite confirmar nuevamente un evento publicado
        assertEquals(0, repository.marcarPublicado(
                evento.getId(),
                instanciaId,
                LocalDateTime.now()
        ));
    }

    @Test
    @Transactional
    void debeDevolverAPendienteSoloSiPerteneceALaInstancia() {

        UUID instanciaId = UUID.randomUUID();
        UUID otraInstancia = UUID.randomUUID();

        OutboxEvento evento = crearEvento(instanciaId);
        repository.saveAndFlush(evento);

        // Una instancia diferente no puede liberar el evento
        int incorrecto = repository.devolverAPendiente(
                evento.getId(),
                otraInstancia
        );

        assertEquals(0, incorrecto);

        // La instancia propietaria puede devolverlo a pendiente
        int correcto = repository.devolverAPendiente(
                evento.getId(),
                instanciaId
        );

        assertEquals(1, correcto);

        entityManager.clear();

        OutboxEvento actualizado = repository
                .findById(evento.getId())
                .orElseThrow();

        assertEquals("PENDIENTE", actualizado.getEstado());
        assertNull(actualizado.getFechaReclamo());
        assertNull(actualizado.getReclamadoPor());

        // El evento ya no está EN_PROCESO
        assertEquals(0, repository.devolverAPendiente(
                evento.getId(),
                instanciaId
        ));
    }

    private OutboxEvento crearEvento(UUID instanciaId) {

        OutboxEvento evento = new OutboxEvento();

        evento.setId(UUID.randomUUID());
        evento.setCorrelationId(UUID.randomUUID());
        evento.setTipo("test.estados");
        evento.setExchange("vuelos.events");
        evento.setRoutingKey("test.estados");
        evento.setPayload("{}");

        evento.setEstado("EN_PROCESO");
        evento.setFechaReclamo(LocalDateTime.now());
        evento.setReclamadoPor(instanciaId);
        evento.setIntentos(1);

        return evento;
    }
}

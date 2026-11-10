package com.empresa.reservas.integration;

import com.empresa.reservas.entity.Reserva;
import com.empresa.reservas.repository.ReservaRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;

import java.math.BigDecimal;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
class ReservaRepositoryIntegrationTest {

    @Autowired
    private ReservaRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void debeGuardarYConsultarReservaEnPostgreSQL() {
        Reserva reserva = new Reserva();

        reserva.setVueloId(15);
        reserva.setUsuarioId(7);
        reserva.setAsiento("12A");
        reserva.setEstado("PENDIENTE");
        reserva.setTotal(new BigDecimal("150.00"));
        reserva.setIdempotencyKey("test-" + UUID.randomUUID());
        reserva.setCorrelationId(UUID.randomUUID());
        reserva.setToken(UUID.randomUUID());

        Reserva guardada = repository.saveAndFlush(reserva);

        assertNotNull(guardada.getId());

        // Vaciar el contexto de persistencia para forzar una lectura real.
        entityManager.clear();

        Reserva encontrada = repository.findById(
                guardada.getId()
        ).orElseThrow();

        assertEquals("12A", encontrada.getAsiento());
        assertEquals("PENDIENTE", encontrada.getEstado());
        assertNotNull(encontrada.getCorrelationId());
        assertNotNull(encontrada.getToken());
    }

    @Test
    void noDebePermitirDosReservasConMismaClaveIdempotencia() {

        String clave = "duplicada-" + UUID.randomUUID();

        Reserva primera = new Reserva();
        primera.setVueloId(15);
        primera.setUsuarioId(7);
        primera.setAsiento("12A");
        primera.setEstado("PENDIENTE");
        primera.setTotal(new BigDecimal("150.00"));
        primera.setIdempotencyKey(clave);
        primera.setCorrelationId(UUID.randomUUID());
        primera.setToken(UUID.randomUUID());

        repository.saveAndFlush(primera);

        Reserva segunda = new Reserva();
        segunda.setVueloId(15);
        segunda.setUsuarioId(7);
        segunda.setAsiento("14B");
        segunda.setEstado("PENDIENTE");
        segunda.setTotal(new BigDecimal("150.00"));
        segunda.setIdempotencyKey(clave);
        segunda.setCorrelationId(UUID.randomUUID());
        segunda.setToken(UUID.randomUUID());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(segunda)
        );
    }

}

package com.empresa.reservas.integration;

import com.empresa.reservas.entity.Reserva;
import com.empresa.reservas.event.ReservaEventoFactory;
import com.empresa.reservas.repository.ReservaRepository;
import com.empresa.reservas.service.ReservaPersistenceService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
class ReservaOutboxRollbackIntegrationTest {

    @Autowired
    private ReservaPersistenceService persistenceService;

    @Autowired
    private ReservaRepository reservaRepository;

    @MockBean
    private ReservaEventoFactory eventoFactory;

    @Test
    void falloAlGenerarEventoDebeRevertirReserva() {

        String clave = "rollback-" + UUID.randomUUID();

        Reserva reserva = new Reserva();
        reserva.setVueloId(15);
        reserva.setUsuarioId(7);
        reserva.setAsiento("14C");
        reserva.setTotal(new BigDecimal("150.00"));
        reserva.setIdempotencyKey(clave);
        reserva.setEstado("PENDIENTE");
        reserva.setFechaCreacion(LocalDateTime.now());
        reserva.setCorrelationId(UUID.randomUUID());
        reserva.setToken(UUID.randomUUID());

        when(eventoFactory.crearEventoReservaCreada(any(Reserva.class)))
                .thenThrow(new IllegalStateException(
                        "Fallo simulado al generar evento"
                ));

        assertThrows(IllegalStateException.class,
                () -> persistenceService.guardarConEvento(reserva));

        assertTrue(
                reservaRepository.findByIdempotencyKey(clave).isEmpty(),
                "La reserva no debe persistir si falla el evento"
        );
    }
}

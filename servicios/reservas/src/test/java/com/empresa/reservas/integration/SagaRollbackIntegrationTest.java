package com.empresa.reservas.integration;

import com.empresa.reservas.dto.CrearReservaRequest;
import com.empresa.reservas.entity.Reserva;
import com.empresa.reservas.repository.ReservaRepository;
import com.empresa.reservas.saga.EventoSaga;
import com.empresa.reservas.saga.SagaService;
import com.empresa.reservas.service.ReservaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"outbox.publicacion.habilitada=false", "outbox.recuperacion.habilitada=false"})
class SagaRollbackIntegrationTest {
    @Autowired SagaService saga;
    @Autowired ReservaService service;
    @Autowired ReservaRepository reservas;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TransactionTemplate tx;
    Reserva reserva;

    @BeforeEach
    void preparar() {
        reserva = service.crearReserva(new CrearReservaRequest(1, 1, "3C", new BigDecimal("100.00"), "saga-rollback-" + UUID.randomUUID()));
    }

    @AfterEach
    void limpiar() {
        tx.executeWithoutResult(s -> {
            jdbc.update("delete from saga_inbox where correlation_id=?", reserva.getCorrelationId());
            jdbc.update("delete from outbox_eventos where correlation_id=?", reserva.getCorrelationId());
            reservas.deleteById(reserva.getId());
        });
    }

    EventoSaga evento() {
        return new EventoSaga(UUID.randomUUID(), reserva.getCorrelationId(), "2026-11-10T12:00:00Z",
                mapper.valueToTree(Map.of("reservaId", reserva.getId(), "token", reserva.getToken())));
    }

    @Test
    void falloOutboxRevierteEstadoSenalEInboxYPermiteReintentar() {
        saga.procesar("pago.exitoso", evento());
        EventoSaga bloqueo = evento();
        jdbc.execute("alter table outbox_eventos add constraint test_saga_rechazo check(correlation_id <> '"
                + reserva.getCorrelationId() + "' OR tipo <> 'reserva.confirmada') NOT VALID");
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> saga.procesar("asiento.bloqueado", bloqueo));
            Reserva persistida = reservas.findById(reserva.getId()).orElseThrow();
            assertEquals("PENDIENTE", persistida.getEstado());
            assertFalse(persistida.isAsientoBloqueado());
            assertEquals(1, jdbc.queryForObject("select count(*) from saga_inbox where correlation_id=?", Integer.class, reserva.getCorrelationId()));
        } finally {
            jdbc.execute("alter table outbox_eventos drop constraint test_saga_rechazo");
        }
        saga.procesar("asiento.bloqueado", bloqueo);
        assertEquals("CONFIRMADA", reservas.findById(reserva.getId()).orElseThrow().getEstado());
    }

    @Test
    void senalesConcurrentesConfirmanUnaVez() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<?> bloqueo = pool.submit(() -> procesarTrasBarrera(gate, "asiento.bloqueado"));
            Future<?> pago = pool.submit(() -> procesarTrasBarrera(gate, "pago.exitoso"));
            gate.countDown();
            bloqueo.get(10, TimeUnit.SECONDS);
            pago.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals("CONFIRMADA", reservas.findById(reserva.getId()).orElseThrow().getEstado());
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_eventos where correlation_id=? and tipo='reserva.confirmada'", Integer.class, reserva.getCorrelationId()));
    }

    private void procesarTrasBarrera(CountDownLatch gate, String tipo) {
        try {
            gate.await();
            saga.procesar(tipo, evento());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}

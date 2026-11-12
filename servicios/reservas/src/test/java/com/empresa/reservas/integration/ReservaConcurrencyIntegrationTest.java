package com.empresa.reservas.integration;

import com.empresa.reservas.dto.CrearReservaRequest;
import com.empresa.reservas.entity.Reserva;
import com.empresa.reservas.service.ReservaService;
import com.empresa.reservas.repository.ReservaRepository;
import com.empresa.reservas.repository.OutboxEventoRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ReservaConcurrencyIntegrationTest {

        @Autowired
        private ReservaService reservaService;
        @Autowired
        private ReservaRepository reservaRepository;

        @Autowired
        private OutboxEventoRepository outboxRepository;

        @Autowired
        private TransactionTemplate transactionTemplate;

        @Test
        void solicitudesSimultaneasDebenRetornarLaMismaReserva()
                        throws Exception {

                String clave = "concurrente-" + UUID.randomUUID();

                CrearReservaRequest request = new CrearReservaRequest(
                                15,
                                7,
                                "12A",
                                new BigDecimal("150.00"),
                                clave);

                ExecutorService executor = Executors.newFixedThreadPool(2);

                CountDownLatch inicio = new CountDownLatch(1);

                try {
                        Callable<Reserva> tarea = () -> {
                                inicio.await();
                                return reservaService.crearReserva(request);
                        };

                        Future<Reserva> primera = executor.submit(tarea);
                        Future<Reserva> segunda = executor.submit(tarea);

                        inicio.countDown();

                        Reserva resultado1 = primera.get(15, TimeUnit.SECONDS);

                        Reserva resultado2 = segunda.get(15, TimeUnit.SECONDS);

                        assertNotNull(resultado1.getId());
                        assertEquals(resultado1.getId(), resultado2.getId());

                        assertEquals(
                                        resultado1.getCorrelationId(),
                                        resultado2.getCorrelationId());

                } finally {

                        transactionTemplate.executeWithoutResult(status -> {
                                reservaRepository.findByIdempotencyKey(clave)
                                                .ifPresent(reserva -> {
                                                        outboxRepository.findByCorrelationId(
                                                                        reserva.getCorrelationId())
                                                                        .ifPresent(outboxRepository::delete);

                                                        reservaRepository.delete(reserva);
                                                });
                        });
                }
        }
}

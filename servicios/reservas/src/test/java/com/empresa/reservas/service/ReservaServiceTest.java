package com.empresa.reservas.service;

import com.empresa.reservas.entity.Reserva;
import com.empresa.reservas.repository.ReservaRepository;
import com.empresa.reservas.dto.CrearReservaRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.math.BigDecimal;

import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservaServiceTest {
        private final ReservaRepository repository = mock(ReservaRepository.class);

        private final ReservaPersistenceService persistenceService = mock(ReservaPersistenceService.class);

        private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

        private final ReservaService service = new ReservaService(
                        repository,
                        persistenceService,
                        transactionTemplate);

        @Test
        void buscarPorIdDebeRetornarReservaExistente() {
                Reserva reserva = new Reserva();

                when(repository.findById(1))
                                .thenReturn(Optional.of(reserva));

                Optional<Reserva> resultado = service.buscarPorId(1);

                assertTrue(resultado.isPresent());
                assertSame(reserva, resultado.get());

                verify(repository).findById(1);
        }

        @Test
        void buscarPorIdempotencyKeyDebeRetornarReservaExistente() {
                Reserva reserva = new Reserva();
                String clave = "reserva-prueba-001";

                when(repository.findByIdempotencyKey(clave))
                                .thenReturn(Optional.of(reserva));

                Optional<Reserva> resultado = service.buscarPorIdempotencyKey(clave);

                assertTrue(resultado.isPresent());
                assertSame(reserva, resultado.get());

                verify(repository).findByIdempotencyKey(clave);
        }

        @Test
        void crearReservaDebeGenerarIdentificadoresSaga() {

                CrearReservaRequest request = new CrearReservaRequest(
                                15,
                                7,
                                "12A",
                                new BigDecimal("150.00"),
                                "reserva-001");

                when(repository.findByIdempotencyKey("reserva-001"))
                                .thenReturn(Optional.empty());

                when(persistenceService.guardarConEvento(any(Reserva.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                Reserva resultado = service.crearReserva(request);

                assertNotNull(resultado);
                assertEquals("PENDIENTE", resultado.getEstado());

                assertEquals(15, resultado.getVueloId());
                assertEquals(7, resultado.getUsuarioId());
                assertEquals("12A", resultado.getAsiento());

                assertNotNull(resultado.getCorrelationId());
                assertNotNull(resultado.getToken());

                assertNotEquals(
                                resultado.getCorrelationId(),
                                resultado.getToken());

                assertEquals(
                                "reserva-001",
                                resultado.getIdempotencyKey());

                ArgumentCaptor<Reserva> captor = ArgumentCaptor.forClass(Reserva.class);

                verify(persistenceService).guardarConEvento(captor.capture());

                assertSame(resultado, captor.getValue());
        }

        @Test
        void crearReservaRepetidaDebeRetornarLaExistente() {

                CrearReservaRequest request = new CrearReservaRequest(
                                15,
                                7,
                                "12A",
                                new BigDecimal("150.00"),
                                "reserva-001");

                Reserva existente = new Reserva();
                existente.setVueloId(15);
                existente.setUsuarioId(7);
                existente.setAsiento("12A");
                existente.setTotal(new BigDecimal("150.00"));
                existente.setIdempotencyKey("reserva-001");
                existente.setEstado("PENDIENTE");

                when(repository.findByIdempotencyKey("reserva-001"))
                                .thenReturn(Optional.of(existente));

                Reserva resultado = service.crearReserva(request);

                assertSame(existente, resultado);
                assertEquals("PENDIENTE", resultado.getEstado());

                verify(persistenceService, never()).guardar(any(Reserva.class));
                verify(repository).findByIdempotencyKey("reserva-001");
        }

        @Test
        void crearReservaConClaveReutilizadaYDatosDiferentesDebeFallar() {

                CrearReservaRequest request = new CrearReservaRequest(
                                15,
                                7,
                                "14B",
                                new BigDecimal("150.00"),
                                "reserva-001");

                Reserva existente = new Reserva();
                existente.setVueloId(15);
                existente.setUsuarioId(7);
                existente.setAsiento("12A");
                existente.setTotal(new BigDecimal("150.00"));
                existente.setIdempotencyKey("reserva-001");

                when(repository.findByIdempotencyKey("reserva-001"))
                                .thenReturn(Optional.of(existente));

                IllegalArgumentException error = assertThrows(
                                IllegalArgumentException.class,
                                () -> service.crearReserva(request));

                assertEquals(
                                "Clave de idempotencia reutilizada con datos diferentes",
                                error.getMessage());

                verify(repository, never()).save(any(Reserva.class));
        }

        @Test
        void conflictoConcurrenteDebeRecuperarReservaExistente() {

                CrearReservaRequest request = new CrearReservaRequest(
                                15,
                                7,
                                "12A",
                                new BigDecimal("150.00"),
                                "reserva-concurrente-001");

                Reserva existente = new Reserva();
                existente.setVueloId(15);
                existente.setUsuarioId(7);
                existente.setAsiento("12A");
                existente.setTotal(new BigDecimal("150.00"));
                existente.setIdempotencyKey("reserva-concurrente-001");
                existente.setEstado("PENDIENTE");

                // La primera consulta no encuentra ninguna reserva.
                when(repository.findByIdempotencyKey("reserva-concurrente-001"))
                                .thenReturn(Optional.empty(), Optional.of(existente));

                // Simulamos que otra solicitud guardó primero.
                when(persistenceService.guardarConEvento(any(Reserva.class)))
                                .thenThrow(new DataIntegrityViolationException(
                                                "Clave de idempotencia duplicada"));

                // Ejecutamos el callback transaccional simulado.
                when(transactionTemplate.execute(any(TransactionCallback.class)))
                                .thenAnswer(invocation -> {
                                        TransactionCallback<?> callback = invocation.getArgument(0);

                                        TransactionStatus status = mock(TransactionStatus.class);

                                        return callback.doInTransaction(status);
                                });

                Reserva resultado = service.crearReserva(request);

                assertSame(existente, resultado);

                verify(persistenceService).guardarConEvento(any(Reserva.class));

                verify(repository, times(2))
                                .findByIdempotencyKey("reserva-concurrente-001");
        }

    @org.junit.jupiter.api.Test
    void rechazaImportesQueLaBaseRedondearia() {
        var request = new com.empresa.reservas.dto.CrearReservaRequest(1,1,"1A",new java.math.BigDecimal("1.001"),"precision");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> service.crearReserva(request));
    }

    @org.junit.jupiter.api.Test
    void rechazaImporteFueraDePrecisionSql() {
        var request = new com.empresa.reservas.dto.CrearReservaRequest(1,1,"1A",new java.math.BigDecimal("100000000.00"),"precision");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> service.crearReserva(request));
    }
}

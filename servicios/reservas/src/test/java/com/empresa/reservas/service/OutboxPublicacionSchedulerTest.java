package com.empresa.reservas.service;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class OutboxPublicacionSchedulerTest {

    @Test
    void debeInvocarProcesamientoDeEventosPendientes() {

        OutboxProcesamientoService procesamientoService =
                mock(OutboxProcesamientoService.class);

        OutboxPublicacionScheduler scheduler =
                new OutboxPublicacionScheduler(procesamientoService);

        scheduler.publicarPendientes();

        verify(procesamientoService, times(1))
                .procesarPendientes();
    }

    @Test
    void debeManejarExcepcionDelProcesamiento() {

        OutboxProcesamientoService procesamientoService =
                mock(OutboxProcesamientoService.class);

        doThrow(new RuntimeException("Error simulado"))
                .when(procesamientoService)
                .procesarPendientes();

        OutboxPublicacionScheduler scheduler =
                new OutboxPublicacionScheduler(procesamientoService);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                scheduler::publicarPendientes
        );

        verify(procesamientoService, times(1))
                .procesarPendientes();
    }
}

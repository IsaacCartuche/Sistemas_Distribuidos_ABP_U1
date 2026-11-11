package com.empresa.pagos.service;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class OutboxRecuperacionSchedulerTest {

    @Test
    void debeInvocarServicioDeRecuperacion() {

        OutboxRecuperacionService recuperacionService =
                mock(OutboxRecuperacionService.class);

        OutboxRecuperacionScheduler scheduler =
                new OutboxRecuperacionScheduler(recuperacionService);

        scheduler.recuperarEventos();

        verify(recuperacionService, times(1))
                .recuperarAbandonados();
    }
}
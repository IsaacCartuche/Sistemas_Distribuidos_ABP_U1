package com.empresa.reservas.service;

import com.empresa.reservas.repository.OutboxEventoRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxRecuperacionServiceTest {

    @Test
    void debeRecuperarEventosAbandonadosDespuesDeCincoMinutos() {

        OutboxEventoRepository repository =
                mock(OutboxEventoRepository.class);

        OutboxRecuperacionService service =
                new OutboxRecuperacionService(repository);

        when(repository.recuperarEventosAbandonados(
                any(LocalDateTime.class)
        )).thenReturn(2);

        LocalDateTime antes = LocalDateTime.now().minusMinutes(5);

        int recuperados = service.recuperarAbandonados();

        LocalDateTime despues = LocalDateTime.now().minusMinutes(5);

        assertEquals(2, recuperados);

        var captor = org.mockito.ArgumentCaptor
                .forClass(LocalDateTime.class);

        verify(repository).recuperarEventosAbandonados(
                captor.capture()
        );

        LocalDateTime limite = captor.getValue();

        assertFalse(limite.isBefore(antes));
        assertFalse(limite.isAfter(despues));
    }
}

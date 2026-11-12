package com.empresa.notificaciones.consumer;

import com.empresa.notificaciones.dto.NotificacionEntradaDTO;
import com.empresa.notificaciones.service.NotificacionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventoConsumerTest {

    @Mock
    private NotificacionService notificacionService;

    @InjectMocks
    private EventoConsumer consumer;

    @Test
    void recibir_eventoValido_delegaAlServicio() {
        NotificacionEntradaDTO evento = new NotificacionEntradaDTO("u1", "PAGO_EXITOSO", "Pago recibido");

        consumer.recibir(evento);

        verify(notificacionService, times(1)).procesarEvento(evento);
    }

    @Test
    void recibir_usuarioIdVacio_lanzaExcepcionYNoDelegar() {
        NotificacionEntradaDTO evento = new NotificacionEntradaDTO("", "T", "m");

        assertThrows(IllegalArgumentException.class, () -> consumer.recibir(evento));
        verifyNoInteractions(notificacionService);
    }
}

package com.empresa.websocket.consumer;

import com.empresa.websocket.dto.NotificacionDTO;
import com.empresa.websocket.service.NotificacionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificacionConsumerTest {

    @Mock
    private NotificacionService notificacionService;

    @InjectMocks
    private NotificacionConsumer consumer;

    @Test
    void recibir_notificacionValida_enviaWebSocket() {
        NotificacionDTO dto = new NotificacionDTO("usuario-1", "RESERVA_CREADA", "Tu reserva fue creada", LocalDateTime.now());

        consumer.recibir(dto);

        verify(notificacionService, times(1)).enviarANotificacion("usuario-1", dto);
    }

    @Test
    void recibir_usuarioIdNulo_lanzaExcepcionYNoEnvía() {
        NotificacionDTO dto = new NotificacionDTO(null, "TIPO", "msg", LocalDateTime.now());

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> consumer.recibir(dto)
        );

        verifyNoInteractions(notificacionService);
    }

    @Test
    void recibir_usuarioIdVacio_lanzaExcepcionYNoEnvía() {
        NotificacionDTO dto = new NotificacionDTO("   ", "TIPO", "msg", LocalDateTime.now());

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> consumer.recibir(dto)
        );

        verifyNoInteractions(notificacionService);
    }
}

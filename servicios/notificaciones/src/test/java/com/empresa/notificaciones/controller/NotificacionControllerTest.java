package com.empresa.notificaciones.controller;

import com.empresa.notificaciones.dto.NotificacionRespuestaDTO;
import com.empresa.notificaciones.service.NotificacionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificacionControllerTest {

    @Mock
    private NotificacionService notificacionService;

    @InjectMocks
    private NotificacionController controller;

    @Test
    void listar_devuelveNotificacionesDelUsuario() {
        NotificacionRespuestaDTO dto = new NotificacionRespuestaDTO(
                1L, "u1", "RESERVA_CREADA", "Reserva creada", LocalDateTime.now(), false
        );
        when(notificacionService.listarPorUsuario("u1")).thenReturn(List.of(dto));

        ResponseEntity<List<NotificacionRespuestaDTO>> response = controller.listar("u1");

        assertEquals(200, response.getStatusCodeValue());
        assertEquals(1, response.getBody().size());
        assertEquals("u1", response.getBody().get(0).usuarioId());
    }

    @Test
    void marcarLeida_llamaAlServicio() {
        ResponseEntity<Void> response = controller.marcarLeida(1L, "u1");

        assertEquals(204, response.getStatusCodeValue());
        verify(notificacionService, times(1)).marcarLeida(1L, "u1");
    }
}

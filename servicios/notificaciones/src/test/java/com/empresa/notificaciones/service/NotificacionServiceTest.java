package com.empresa.notificaciones.service;

import com.empresa.notificaciones.dto.NotificacionEntradaDTO;
import com.empresa.notificaciones.entity.Notificacion;
import com.empresa.notificaciones.repository.NotificacionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificacionServiceTest {

    @Mock
    private NotificacionRepository repository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private NotificacionService service;

    @Test
    void procesarEvento_guardaEnBD_yPublicaRabbitMQ() {
        NotificacionEntradaDTO evento = new NotificacionEntradaDTO("u1", "RESERVA_CREADA", "Reserva confirmada");
        Notificacion guardada = new Notificacion("u1", "RESERVA_CREADA", "Reserva confirmada", LocalDateTime.now());
        guardada.setId(1L);

        when(repository.save(any(Notificacion.class))).thenReturn(guardada);

        service.procesarEvento(evento);

        verify(repository, times(1)).save(any(Notificacion.class));
        verify(rabbitTemplate, times(1)).convertAndSend(
                "vuelos.events",
                "notificacion.baja",
                argThat(dto -> dto.usuarioId().equals("u1") && dto.tipo().equals("RESERVA_CREADA"))
        );
    }

    @Test
    void procesarEvento_usuarioIdNulo_lanzaExcepcion() {
        NotificacionEntradaDTO evento = new NotificacionEntradaDTO(null, "T", "m");

        assertThrows(IllegalArgumentException.class, () -> service.procesarEvento(evento));
        verifyNoInteractions(repository);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void listarPorUsuario_devuelveListaOrdenada() {
        when(repository.findByUsuarioIdOrderByFechaEnvioDesc("u1")).thenReturn(List.of());

        List<com.empresa.notificaciones.dto.NotificacionRespuestaDTO> resultado = service.listarPorUsuario("u1");

        assertNotNull(resultado);
        assertTrue(resultado.isEmpty());
        verify(repository, times(1)).findByUsuarioIdOrderByFechaEnvioDesc("u1");
    }
}

package com.empresa.notificaciones.service;

import com.empresa.notificaciones.dto.NotificacionEntradaDTO;
import com.empresa.notificaciones.dto.NotificacionRespuestaDTO;
import com.empresa.notificaciones.dto.NotificacionSalidaDTO;
import com.empresa.notificaciones.entity.Notificacion;
import com.empresa.notificaciones.repository.NotificacionRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Lógica de negocio de notificaciones.
 * Guarda cada evento en su propia BD (db_notificaciones) y lo reenvía a la cola
 * notificaciones.baja para que WebSocket Server lo entregue en tiempo real.
 * No depende de clases de otros microservicios.
 */
@Service
public class NotificacionService {

    public static final String EXCHANGE = "vuelos.events";
    public static final String ROUTING_KEY_NOTIFICACION_BAJA = "notificacion.baja";

    private final NotificacionRepository repository;
    private final RabbitTemplate rabbitTemplate;

    public NotificacionService(NotificacionRepository repository, RabbitTemplate rabbitTemplate) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Transactional
    public void procesarEvento(NotificacionEntradaDTO evento) {
        Notificacion notificacion = new Notificacion(
                evento.usuarioId(),
                evento.tipo(),
                evento.mensaje(),
                LocalDateTime.now()
        );
        Notificacion guardada = repository.save(notificacion);

        // Reenvío a cola de baja prioridad para entrega vía WebSocket
        rabbitTemplate.convertAndSend(
                EXCHANGE,
                ROUTING_KEY_NOTIFICACION_BAJA,
                new NotificacionSalidaDTO(
                        guardada.getUsuarioId(),
                        guardada.getTipo(),
                        guardada.getMensaje(),
                        guardada.getFechaEnvio()
                )
        );
    }

    @Transactional(readOnly = true)
    public List<NotificacionRespuestaDTO> listarPorUsuario(String usuarioId) {
        return repository.findByUsuarioIdOrderByFechaEnvioDesc(usuarioId).stream()
                .map(n -> new NotificacionRespuestaDTO(
                        n.getId(),
                        n.getUsuarioId(),
                        n.getTipo(),
                        n.getMensaje(),
                        n.getFechaEnvio(),
                        n.isLeida()
                ))
                .collect(Collectors.toList());
    }

    @Transactional
    public void marcarLeida(Long id, String usuarioId) {
        Notificacion n = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Notificación no encontrada: " + id));
        if (!n.getUsuarioId().equals(usuarioId)) {
            throw new IllegalArgumentException("No autorizado para esta notificación");
        }
        n.setLeida(true);
        repository.save(n);
    }
}

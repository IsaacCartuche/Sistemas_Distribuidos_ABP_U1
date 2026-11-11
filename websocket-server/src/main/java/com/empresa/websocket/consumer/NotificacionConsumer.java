package com.empresa.websocket.consumer;

import com.empresa.websocket.dto.NotificacionDTO;
import com.empresa.websocket.service.NotificacionService;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Consumidor de la cola RabbitMQ 'notificaciones.baja' (prioridad 1).
 * Recibe eventos de negocio (reserva creada, pago exitoso, etc.) y los reenvía por WebSocket.
 */
@Component
public class NotificacionConsumer {

    private final NotificacionService notificacionService;

    public NotificacionConsumer(NotificacionService notificacionService) {
        this.notificacionService = notificacionService;
    }

    @Bean
    public Queue notificacionesBajaQueue() {
        return org.springframework.amqp.core.QueueBuilder.durable("notificaciones.baja")
                .withArgument("x-max-priority", 1)
                .withArgument("x-dead-letter-exchange", "vuelos.dlx")
                .withArgument("x-dead-letter-routing-key", "notificacion.baja.dlq")
                .build();
    }

    @RabbitListener(queues = "notificaciones.baja")
    public void recibir(@Payload NotificacionDTO notificacion) {
        if (notificacion.usuarioId() == null || notificacion.usuarioId().isBlank()) {
            throw new IllegalArgumentException("usuarioId es requerido");
        }
        notificacionService.enviarANotificacion(notificacion.usuarioId(), notificacion);
    }
}

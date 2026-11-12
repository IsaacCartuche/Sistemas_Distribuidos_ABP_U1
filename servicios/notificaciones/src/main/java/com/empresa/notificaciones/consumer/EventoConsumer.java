package com.empresa.notificaciones.consumer;

import com.empresa.notificaciones.dto.NotificacionEntradaDTO;
import com.empresa.notificaciones.service.NotificacionService;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Consumidor de eventos de negocio desde RabbitMQ.
 * Escucha la cola notificaciones.entrada (se crea automáticamente si no existe).
 * Otros servicios publican sus eventos al exchange vuelos.events; este servicio
 * los consume sin llamadas REST directas, cumpliendo el requisito de comunicación asíncrona.
 */
@Component
public class EventoConsumer {

    private final NotificacionService notificacionService;

    public EventoConsumer(NotificacionService notificacionService) {
        this.notificacionService = notificacionService;
    }

    @Bean
    public Queue notificacionesEntradaQueue() {
        return org.springframework.amqp.core.QueueBuilder.durable("notificaciones.entrada")
                .withArgument("x-max-priority", 5)
                .withArgument("x-dead-letter-exchange", "vuelos.dlx")
                .withArgument("x-dead-letter-routing-key", "notificacion.entrada.dlq")
                .build();
    }

    @RabbitListener(queues = "notificaciones.entrada")
    public void recibir(@Payload NotificacionEntradaDTO evento) {
        if (evento.usuarioId() == null || evento.usuarioId().isBlank()) {
            throw new IllegalArgumentException("usuarioId es requerido");
        }
        notificacionService.procesarEvento(evento);
    }
}

package com.empresa.notificaciones.consumer;

import com.empresa.notificaciones.dto.NotificacionEntradaDTO;
import com.empresa.notificaciones.service.NotificacionService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
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

    @RabbitListener(queues = "notificaciones.entrada")
    public void recibir(@Payload NotificacionEntradaDTO evento) {
        if (evento.usuarioId() == null || evento.usuarioId().isBlank()) {
            throw new IllegalArgumentException("usuarioId es requerido");
        }
        notificacionService.procesarEvento(evento);
    }
}

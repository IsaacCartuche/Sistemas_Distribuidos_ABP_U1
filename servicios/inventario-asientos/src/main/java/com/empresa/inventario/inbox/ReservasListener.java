package com.empresa.inventario.inbox;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "inventario.consumidor.habilitado", havingValue = "true")
public class ReservasListener {
    private final ValidadorReserva validador;
    private final ProcesadorReservas procesador;
    public ReservasListener(ValidadorReserva validador, ProcesadorReservas procesador) {
        this.validador = validador; this.procesador = procesador;
    }

    @Bean
    public Queue inventarioEntradaQueue() {
        // Los argumentos DEBEN coincidir con infra/rabbitmq/definitions.json.
        // Si difieren, RabbitMQ cierra el canal con 406 PRECONDITION_FAILED
        // y el listener nunca arranca.
        return org.springframework.amqp.core.QueueBuilder.durable("inventario.entrada")
                .withArgument("x-max-priority", 5)
                .withArgument("x-dead-letter-exchange", "vuelos.dlx")
                .withArgument("x-dead-letter-routing-key", "inventario.dlq")
                .build();
    }

    @RabbitListener(id = "inventario-reservas", queues = "inventario.entrada", containerFactory = "inventarioListenerFactory")
    public void recibir(Message mensaje) {
        EventoReserva evento = validador.validar(mensaje.getBody(), mensaje.getMessageProperties().getReceivedRoutingKey());
        // El proxy confirma PostgreSQL ANTES de retornar. AUTO ACK del contenedor ocurre después.
        procesador.procesar(evento);
    }
}
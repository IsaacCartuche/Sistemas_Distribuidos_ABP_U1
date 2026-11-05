package com.empresa.usuarios.publisher;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class UsuarioProducer {

    private final RabbitTemplate rabbitTemplate;

    @Value("${app.rabbitmq.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.routing-key}")
    private String routingKey;

    public void publicarUsuarioRegistrado(Long id, String email, String rol) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", id);
        payload.put("email", email);
        payload.put("rol", rol);

        Map<String, Object> event = new HashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("timestamp", Instant.now().toString());
        event.put("payload", payload);

        rabbitTemplate.convertAndSend(exchange, routingKey, event);
    }
}
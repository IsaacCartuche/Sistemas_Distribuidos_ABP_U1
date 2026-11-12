package com.empresa.usuarios.publisher;

import com.empresa.usuarios.dto.EventoUsuarioRegistrado;
import com.empresa.usuarios.model.Usuario;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class UsuarioProducer {

    private final RabbitTemplate rabbitTemplate;

    @Value("${app.rabbitmq.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.routing-key}")
    private String routingKey;

    public void publicarUsuarioRegistrado(Usuario usuario) {
        EventoUsuarioRegistrado evento = EventoUsuarioRegistrado.builder()
                .eventId(UUID.randomUUID().toString())
                .correlationId(usuario.getId().toString())
                .timestamp(Instant.now().toString())
                .payload(EventoUsuarioRegistrado.Payload.builder()
                        .id(usuario.getId())
                        .email(usuario.getEmail())
                        .nombre(usuario.getNombre())
                        .apellido(usuario.getApellido())
                        .rol(usuario.getRol().name())
                        .build())
                .build();

        rabbitTemplate.convertAndSend(exchange, routingKey, evento);
    }
}
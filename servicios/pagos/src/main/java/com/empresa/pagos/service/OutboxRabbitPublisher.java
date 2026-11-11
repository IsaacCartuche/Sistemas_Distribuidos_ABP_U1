
package com.empresa.pagos.service;

import com.empresa.pagos.entity.OutboxEvento;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Service
public class OutboxRabbitPublisher {

    private final RabbitTemplate rabbitTemplate;

    public OutboxRabbitPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public boolean publicar(OutboxEvento evento) {
        MessageProperties propiedades = new MessageProperties();
        propiedades.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        propiedades.setContentEncoding(StandardCharsets.UTF_8.name());
        propiedades.setMessageId(evento.getId().toString());
        propiedades.setDeliveryMode(
                org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);

        Message mensaje = new Message(
                evento.getPayload().getBytes(StandardCharsets.UTF_8),
                propiedades);

        CorrelationData correlacion = new CorrelationData(evento.getId().toString());

        try {
            rabbitTemplate.send(
                    evento.getExchange(),
                    evento.getRoutingKey(),
                    mensaje,
                    correlacion);

            CorrelationData.Confirm confirmacion = correlacion.getFuture().get(5, TimeUnit.SECONDS);

            if (!confirmacion.isAck()) {
                return false;
            }

            // Un ACK no garantiza que exista una cola de destino.
            return correlacion.getReturned() == null;

        } catch (InterruptedException excepcion) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception excepcion) {
            return false;
        }
    }
}

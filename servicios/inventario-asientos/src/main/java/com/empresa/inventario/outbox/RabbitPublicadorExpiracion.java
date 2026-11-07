package com.empresa.inventario.outbox;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;

@Component
public class RabbitPublicadorExpiracion implements PublicadorExpiracion {
    public static final String EXCHANGE = "vuelos.events";
    public static final String ROUTING_KEY = "asiento.expirado";
    private final RabbitTemplate rabbit;
    private final OutboxConfig.Propiedades propiedades;
    private final ValidadorExpiracion validador;

    public RabbitPublicadorExpiracion(RabbitTemplate rabbit, OutboxConfig.Propiedades propiedades,
                                      ValidadorExpiracion validador) {
        this.rabbit = rabbit;
        this.propiedades = propiedades;
        this.validador = validador;
        this.rabbit.setMandatory(true);
    }

    @Override
    public void publicar(OutboxRepository.Evento evento) {
        validador.validar(evento); // Antes de contactar el broker, incluso si este está desconectado.
        // Evita marcar enviado si se desactivan accidentalmente confirms/returns en el despliegue.
        if (!(rabbit.getConnectionFactory() instanceof CachingConnectionFactory factory)
                || !factory.isPublisherConfirms() || !factory.isPublisherReturns()) {
            throw new FalloPublicacion("CONFIRMS_RETURNS_NO_CONFIGURADOS");
        }
        CorrelationData confirmacion = new CorrelationData(UUID.randomUUID().toString());
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding("UTF-8");
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(evento.eventId().toString());
        props.setCorrelationId(evento.correlationId().toString());
        props.setType(ROUTING_KEY);
        props.setPriority(10);
        Message mensaje = new Message(evento.mensaje().getBytes(StandardCharsets.UTF_8), props);
        try {
            rabbit.send(EXCHANGE, ROUTING_KEY, mensaje, confirmacion);
            CorrelationData.Confirm confirm = confirmacion.getFuture()
                    .get(propiedades.confirmTimeoutMs(), TimeUnit.MILLISECONDS);
            // Spring completa el returned message antes del future del confirm correlacionado.
            if (confirmacion.getReturned() != null) throw new FalloPublicacion("SIN_DESTINO");
            if (!confirm.isAck()) throw new FalloPublicacion("BROKER_NACK");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FalloPublicacion("INTERRUMPIDO");
        } catch (TimeoutException exception) {
            throw new FalloPublicacion("CONFIRM_TIMEOUT");
        } catch (AmqpException | ExecutionException exception) {
            throw new FalloPublicacion("BROKER_NO_DISPONIBLE");
        }
    }
}

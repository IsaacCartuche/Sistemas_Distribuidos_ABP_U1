package com.empresa.vuelos.outbox;

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
public class RabbitPublicadorVuelos implements PublicadorVuelos {
    public static final String EXCHANGE = "vuelos.events";
    public static final String ROUTING_KEY = "vuelos.actualizados";
    private final RabbitTemplate rabbit;
    private final ValidadorEventoVuelo validador;
    private final VuelosOutboxConfig.Propiedades propiedades;
    public RabbitPublicadorVuelos(RabbitTemplate rabbit, ValidadorEventoVuelo validador, VuelosOutboxConfig.Propiedades propiedades) {
        this.rabbit = rabbit; this.validador = validador; this.propiedades = propiedades;
        rabbit.setMandatory(true);
    }
    public void publicar(VuelosOutboxRepository.Evento evento) {
        validador.validar(evento);
        if (!(rabbit.getConnectionFactory() instanceof CachingConnectionFactory factory)
                || !factory.isPublisherConfirms() || !factory.isPublisherReturns()) throw new FalloPublicacion("CONFIRMS_RETURNS_NO_CONFIGURADOS");
        var correlacion = new CorrelationData(UUID.randomUUID().toString());
        var props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON); props.setContentEncoding("UTF-8");
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT); props.setType(ROUTING_KEY);
        props.setMessageId(evento.eventId().toString()); props.setCorrelationId(evento.correlationId().toString());
        try {
            rabbit.send(EXCHANGE, ROUTING_KEY, new Message(evento.mensaje().getBytes(StandardCharsets.UTF_8), props), correlacion);
            var confirm = correlacion.getFuture().get(propiedades.confirmTimeoutMs(), TimeUnit.MILLISECONDS);
            if (correlacion.getReturned() != null) throw new FalloPublicacion("SIN_DESTINO");
            if (!confirm.isAck()) throw new FalloPublicacion("BROKER_NACK");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new FalloPublicacion("INTERRUMPIDO");
        } catch (TimeoutException exception) { throw new FalloPublicacion("CONFIRM_TIMEOUT"); }
        catch (ExecutionException | AmqpException exception) { throw new FalloPublicacion("BROKER_NO_DISPONIBLE"); }
    }
}

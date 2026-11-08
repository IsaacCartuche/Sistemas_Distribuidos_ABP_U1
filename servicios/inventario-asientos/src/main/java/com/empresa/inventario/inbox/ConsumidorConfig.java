package com.empresa.inventario.inbox;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.policy.SimpleRetryPolicy;
import java.util.Map;

@Configuration
@ConditionalOnProperty(name = "inventario.consumidor.habilitado", havingValue = "true")
public class ConsumidorConfig {
    @Bean
    SimpleRabbitListenerContainerFactory inventarioListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        factory.setMissingQueuesFatal(true);
        var policy = new SimpleRetryPolicy(3, Map.of(EventoReservaInvalido.class, false), true, true);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().retryPolicy(policy)
                .backOffOptions(100, 2, 500)
                .recoverer((message, cause) -> {
                    throw new AmqpRejectAndDontRequeueException("Inventario: mensaje rechazado sin requeue", cause);
                }).build());
        return factory;
    }
}

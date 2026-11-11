package com.empresa.pagos.saga;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
@Configuration
@ConditionalOnProperty(name="saga.habilitada",havingValue="true")
public class SagaMessaging {
 @Bean public Declarables sagaTopology() {
  var events=new TopicExchange("vuelos.events",true,false);
  var dlx=new TopicExchange("dlq.reservas",true,false);
  // Colas propias: no redeclarar con argumentos distintos las colas compartidas existentes.
  var q=QueueBuilder.durable("pagos.saga.v1").maxPriority(5).deadLetterExchange("dlq.reservas").deadLetterRoutingKey("pagos.saga").build();
  var dead=QueueBuilder.durable("pagos.saga.dlq").build();
  java.util.List<Declarable> all=new java.util.ArrayList<>(java.util.List.of(events,dlx,q,dead,BindingBuilder.bind(dead).to(dlx).with("pagos.saga")));
  for(String key: new String[]{"reserva.creada","asiento.bloqueado","reserva.cancelada"}) all.add(BindingBuilder.bind(q).to(events).with(key));
  return new Declarables(all);
 }
 @Bean public SimpleRabbitListenerContainerFactory sagaListenerFactory(SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory cf) {
  var f=new SimpleRabbitListenerContainerFactory(); configurer.configure(f,cf);
  f.setDefaultRequeueRejected(false);
  f.setAdviceChain(RetryInterceptorBuilder.stateless().maxAttempts(4).backOffOptions(200,2,2000).recoverer(new RejectAndDontRequeueRecoverer()).build());
  return f;
 }
}

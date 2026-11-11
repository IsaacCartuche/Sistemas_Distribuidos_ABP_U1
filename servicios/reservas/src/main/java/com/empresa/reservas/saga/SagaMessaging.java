package com.empresa.reservas.saga;
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
 private final boolean guia;
 public SagaMessaging(@org.springframework.beans.factory.annotation.Value("${saga.topologia:aislada}") String topologia) {
  if(!java.util.Set.of("aislada","guia").contains(topologia)) throw new IllegalArgumentException("saga.topologia debe ser aislada o guia");
  guia="guia".equals(topologia);
 }
 @Bean public String[] sagaColas() {
  return guia ? new String[]{"pagos.media","reservas.alta"} : new String[]{"reservas.saga.v1"};
 }

 @Bean public Declarables sagaTopology() {
  var events=new TopicExchange("vuelos.events",true,false);
  var dlx=new TopicExchange("dlq.reservas",true,false);
  if(guia) {
   // Las colas canonicas son propiedad de infraestructura. Solo declarar bindings:
   // no redeclararlas ni cambiar sus argumentos/prioridades/DLX unilateralmente.
   var dead=QueueBuilder.durable("reservas.saga.dlq").build();
   java.util.List<Declarable> all=new java.util.ArrayList<>(java.util.List.of(events,dlx,dead,BindingBuilder.bind(dead).to(dlx).with("reservas.saga")));
   for(String key:new String[]{"pago.exitoso","pago.fallido","pago.reembolsado"})
    all.add(new Binding("pagos.media",Binding.DestinationType.QUEUE,"vuelos.events",key,null));
   for(String key:new String[]{"asiento.bloqueado","asiento.no_disponible","asiento.expirado","asiento.liberado"})
    all.add(new Binding("reservas.alta",Binding.DestinationType.QUEUE,"vuelos.events",key,null));
   return new Declarables(all);
  }
  // Colas propias: no redeclarar con argumentos distintos las colas compartidas existentes.
  var q=QueueBuilder.durable("reservas.saga.v1").maxPriority(10).deadLetterExchange("dlq.reservas").deadLetterRoutingKey("reservas.saga").build();
  var dead=QueueBuilder.durable("reservas.saga.dlq").build();
  java.util.List<Declarable> all=new java.util.ArrayList<>(java.util.List.of(events,dlx,q,dead,BindingBuilder.bind(dead).to(dlx).with("reservas.saga")));
  for(String key: new String[]{"asiento.bloqueado","asiento.no_disponible","asiento.expirado","asiento.liberado","pago.exitoso","pago.fallido","pago.reembolsado"}) all.add(BindingBuilder.bind(q).to(events).with(key));
  return new Declarables(all);
 }
 @Bean public SimpleRabbitListenerContainerFactory sagaListenerFactory(SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory cf) {
  var f=new SimpleRabbitListenerContainerFactory(); configurer.configure(f,cf);
  f.setMissingQueuesFatal(true);
  f.setDefaultRequeueRejected(false);
  f.setAdviceChain(RetryInterceptorBuilder.stateless().maxAttempts(4).backOffOptions(200,2,2000).recoverer(new RejectAndDontRequeueRecoverer()).build());
  return f;
 }
}

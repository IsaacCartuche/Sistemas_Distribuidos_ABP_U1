package com.empresa.reservas.integration;
@org.springframework.boot.test.context.TestConfiguration
public class OutboxTestTopology {
 @org.springframework.context.annotation.Bean
 public org.springframework.amqp.core.Declarables testBindings(){
 var q=new org.springframework.amqp.core.Queue(OutboxRabbitPublisherIntegrationTest.COLA_PRUEBA,false,true,true);
 var e=new org.springframework.amqp.core.TopicExchange("vuelos.events",true,false);
 return new org.springframework.amqp.core.Declarables(e,q,org.springframework.amqp.core.BindingBuilder.bind(q).to(e).with("reserva.creada"));
 }
}

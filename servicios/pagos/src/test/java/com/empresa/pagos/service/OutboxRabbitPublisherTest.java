package com.empresa.pagos.service;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import com.empresa.pagos.entity.OutboxEvento;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class OutboxRabbitPublisherTest {
 @Test void brokerCaidoDevuelveFalso(){
  var cf=new CachingConnectionFactory("127.0.0.1",1);cf.setConnectionTimeout(500);
  try{var t=new RabbitTemplate(cf);t.setMandatory(true);var p=new OutboxRabbitPublisher(t);var e=new OutboxEvento();e.setId(UUID.randomUUID());e.setExchange("vuelos.events");e.setRoutingKey("pago.exitoso");e.setPayload("{}");assertFalse(p.publicar(e));}finally{cf.destroy();}
 }
}

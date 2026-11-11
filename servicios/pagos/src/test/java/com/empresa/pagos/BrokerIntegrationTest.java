package com.empresa.pagos;
import com.empresa.pagos.entity.OutboxEvento;
import com.empresa.pagos.service.OutboxRabbitPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"saga.habilitada=true","outbox.publicacion.habilitada=false","outbox.recuperacion.habilitada=false"})
class BrokerIntegrationTest {
 @Autowired RabbitTemplate rabbit; @Autowired RabbitAdmin admin; @Autowired OutboxRabbitPublisher publisher;
 @Test void mensajeInvalidoVaADlqDespuesDeReintentos(){
  String name="pagos.test.dlq."+UUID.randomUUID();
  var q=new Queue(name,false,true,false);admin.declareQueue(q);
  admin.declareBinding(BindingBuilder.bind(q).to(new TopicExchange("dlq.reservas")).with("pagos.saga"));
  String id=UUID.randomUUID().toString();var props=new MessageProperties();props.setMessageId(id);props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
  rabbit.send("","pagos.saga.v1",new Message("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),props));
  try {
   Message dead=null;long limite=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
   while(System.nanoTime()<limite){
    Message candidato=rabbit.receive(name,1000);
    if(candidato!=null && id.equals(candidato.getMessageProperties().getMessageId())){dead=candidato;break;}
   }
   assertNotNull(dead);assertNotNull(dead.getMessageProperties().getHeaders().get("x-death"));
  }finally{admin.deleteQueue(name);}
 }
 @Test void ackSinRutaNoSeConsideraPublicado(){var e=new OutboxEvento();e.setId(UUID.randomUUID());e.setExchange("vuelos.events");e.setRoutingKey("prueba.sin.destino."+UUID.randomUUID());e.setPayload("{}");assertFalse(publisher.publicar(e));}
 @Test void publicacionPersistenteConfirmada(){
  String name="pagos.test."+UUID.randomUUID();var q=new Queue(name,false,true,false);admin.declareQueue(q);admin.declareBinding(BindingBuilder.bind(q).to(new TopicExchange("vuelos.events")).with("pago.exitoso"));
  try{var e=new OutboxEvento();e.setId(UUID.randomUUID());e.setExchange("vuelos.events");e.setRoutingKey("pago.exitoso");e.setPayload("{}");assertTrue(publisher.publicar(e));var m=rabbit.receive(name,5000);assertNotNull(m);assertEquals(MessageDeliveryMode.PERSISTENT,m.getMessageProperties().getReceivedDeliveryMode());}finally{admin.deleteQueue(name);}
 }
}

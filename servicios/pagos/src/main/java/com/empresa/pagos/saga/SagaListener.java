package com.empresa.pagos.saga;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
@Component
@ConditionalOnProperty(name="saga.habilitada",havingValue="true")
public class SagaListener {
 private final ObjectMapper mapper; private final SagaService service;
 public SagaListener(ObjectMapper mapper,SagaService service){this.mapper=mapper;this.service=service;}
 @RabbitListener(queues="pagos.saga.v1",containerFactory="sagaListenerFactory")
 public void recibir(Message m) throws java.io.IOException {
  service.procesar(m.getMessageProperties().getReceivedRoutingKey(), mapper.readValue(m.getBody(),EventoSaga.class));
 }
}

package com.empresa.pagos;
import com.empresa.pagos.saga.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"outbox.publicacion.habilitada=false","outbox.recuperacion.habilitada=false"})
class SagaIntegrationTest {
 @Autowired SagaService saga; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 UUID c; UUID token; int reserva;
 @BeforeEach void preparar(){c=UUID.randomUUID();token=UUID.randomUUID();reserva=Math.floorMod(c.hashCode(),2000000000)+1;}
 @AfterEach void limpiar(){tx.executeWithoutResult(s->{jdbc.update("delete from outbox_eventos where correlation_id=?",c);jdbc.update("delete from saga_inbox where correlation_id=?",c);jdbc.update("delete from pago_intentos where correlation_id=?",c);jdbc.update("delete from pago_saga where correlation_id=?",c);});}
 EventoSaga evento(String monto){return new EventoSaga(UUID.randomUUID(),c,"2026-11-10T12:00:00Z",mapper.valueToTree(Map.of("reservaId",reserva,"token",token,"total",new BigDecimal(monto))));}
 String estado(){return jdbc.queryForObject("select estado from pago_saga where correlation_id=?",String.class,c);}
 long intentos(){return jdbc.queryForObject("select count(*) from pago_intentos where correlation_id=?",Long.class,c);}
 long eventos(){return jdbc.queryForObject("select count(*) from outbox_eventos where correlation_id=?",Long.class,c);}
 @Test void duplicadosYReembolso(){var e=evento("100");saga.procesar("reserva.creada",e);saga.procesar("reserva.creada",e);assertEquals(0,intentos());saga.procesar("asiento.bloqueado",evento("100"));saga.procesar("asiento.bloqueado",evento("100"));assertEquals("EXITOSO",estado());saga.procesar("reserva.cancelada",evento("100"));saga.procesar("reserva.cancelada",evento("100"));assertEquals("REEMBOLSADO",estado());assertEquals(2,intentos());assertEquals(2,eventos());}
 @Test void bloqueoAntesDeReserva(){saga.procesar("asiento.bloqueado",evento("100"));assertEquals(0,intentos());saga.procesar("reserva.creada",evento("100"));assertEquals("EXITOSO",estado());assertEquals(1,intentos());}
 @Test void cancelacionAntesDeCobro(){saga.procesar("reserva.cancelada",evento("100"));saga.procesar("asiento.bloqueado",evento("100"));saga.procesar("reserva.creada",evento("100"));assertEquals("CANCELADO",estado());assertEquals(0,intentos());assertEquals(0,eventos());}
 @Test void pagoRechazadoNoSeReintentaComoCobro(){saga.procesar("reserva.creada",evento("20000"));saga.procesar("asiento.bloqueado",evento("20000"));saga.procesar("asiento.bloqueado",evento("20000"));assertEquals("FALLIDO",estado());assertEquals(1,intentos());assertEquals(1,eventos());}
 @Test void importeIncompatibleNoAlteraPago(){saga.procesar("reserva.creada",evento("100"));assertThrows(IllegalArgumentException.class,()->saga.procesar("reserva.creada",evento("200")));assertEquals(1,jdbc.queryForObject("select count(*) from saga_inbox where correlation_id=?",Integer.class,c));}
 @Test void persistenciaFallaRevierteInboxPagoYOutbox(){
  saga.procesar("reserva.creada",evento("100"));
  // Error real de BD al insertar el evento: toda la transaccion debe revertirse.
  jdbc.execute("alter table outbox_eventos add constraint test_rechazo check(correlation_id <> '"+c+"' OR tipo <> 'pago.exitoso') NOT VALID");
  try{assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->saga.procesar("asiento.bloqueado",evento("100")));assertEquals("PENDIENTE",estado());assertEquals(0,intentos());assertEquals(0,eventos());}
  finally{jdbc.execute("alter table outbox_eventos drop constraint test_rechazo");}
  saga.procesar("asiento.bloqueado",evento("100"));assertEquals("EXITOSO",estado());
 }
 @Test void mensajesConcurrentesCobranUnaVez() throws Exception {
  saga.procesar("reserva.creada",evento("100"));var pool=java.util.concurrent.Executors.newFixedThreadPool(4);var gate=new java.util.concurrent.CountDownLatch(1);
  try{var futures=new ArrayList<java.util.concurrent.Future<?>>();for(int i=0;i<4;i++)futures.add(pool.submit(()->{try{gate.await();saga.procesar("asiento.bloqueado",evento("100"));}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new RuntimeException(ex);}}));gate.countDown();for(var future:futures)future.get(10,java.util.concurrent.TimeUnit.SECONDS);}finally{pool.shutdownNow();}
  assertEquals(1,intentos());assertEquals(1,eventos());
 }
}

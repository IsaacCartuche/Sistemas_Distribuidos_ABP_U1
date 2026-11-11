package com.empresa.reservas.integration;
import com.empresa.reservas.saga.*;
import com.empresa.reservas.service.ReservaService;
import com.empresa.reservas.dto.CrearReservaRequest;
import com.empresa.reservas.entity.Reserva;
import com.empresa.reservas.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"outbox.publicacion.habilitada=false","outbox.recuperacion.habilitada=false"})
@Transactional
class SagaIntegrationTest {
 @Autowired SagaService saga; @Autowired ReservaService service; @Autowired ReservaRepository reservas;
 @Autowired OutboxEventoRepository outbox; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc;
 Reserva crear(){return service.crearReserva(new CrearReservaRequest(1,1,"1A",new BigDecimal("120.00"),"saga-"+UUID.randomUUID()));}
 EventoSaga evento(Reserva r){return new EventoSaga(UUID.randomUUID(),r.getCorrelationId(),"2026-11-10T12:00:00Z",mapper.valueToTree(Map.of("reservaId",r.getId(),"token",r.getToken())));}
 @Test void exigeAmbasSenalesPagoPrimero(){var r=crear();var e=evento(r);saga.procesar("pago.exitoso",e);assertEquals("PENDIENTE",r.getEstado());saga.procesar("pago.exitoso",e);saga.procesar("asiento.bloqueado",evento(r));assertEquals("CONFIRMADA",r.getEstado());assertEquals(2,outbox.count());}
 @Test void exigeAmbasSenalesAsientoPrimero(){var r=crear();saga.procesar("asiento.bloqueado",evento(r));assertEquals("PENDIENTE",r.getEstado());saga.procesar("pago.exitoso",evento(r));saga.procesar("pago.exitoso",evento(r));assertEquals("CONFIRMADA",r.getEstado());assertEquals(2,outbox.count());}
 @Test void cancelacionEsTerminal(){var r=crear();saga.procesar("asiento.expirado",evento(r));saga.procesar("pago.exitoso",evento(r));saga.procesar("asiento.bloqueado",evento(r));assertEquals("CANCELADA",r.getEstado());assertEquals(2,outbox.count());}
 @Test void falloPagoCancela(){var r=crear();saga.procesar("pago.fallido",evento(r));assertEquals("CANCELADA",r.getEstado());}
 @Test void rechazaTokenAjeno(){var r=crear();var e=new EventoSaga(UUID.randomUUID(),r.getCorrelationId(),"2026-11-10T12:00:00",mapper.valueToTree(Map.of("reservaId",r.getId(),"token",UUID.randomUUID())));assertThrows(IllegalArgumentException.class,()->saga.procesar("asiento.bloqueado",e));assertEquals("PENDIENTE",r.getEstado());}
 @Test void desconocidaSeReintenta(){var e=new EventoSaga(UUID.randomUUID(),UUID.randomUUID(),"2026-11-10T12:00:00Z",mapper.valueToTree(Map.of("reservaId",123)));assertThrows(IllegalStateException.class,()->saga.procesar("pago.exitoso",e));}
}

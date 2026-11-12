package com.empresa.vuelos.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.net.ConnectException;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublicadorVuelosTest {
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final CachingConnectionFactory factory = mock(CachingConnectionFactory.class);
    private final VuelosOutboxRepository.Evento evento = valido();
    private RabbitPublicadorVuelos publicador;
    @BeforeEach void preparar() {
        when(rabbit.getConnectionFactory()).thenReturn(factory);
        when(factory.isPublisherConfirms()).thenReturn(true);
        when(factory.isPublisherReturns()).thenReturn(true);
        publicador = new RabbitPublicadorVuelos(rabbit, new ValidadorEventoVuelo(new ObjectMapper()),
                new VuelosOutboxConfig.Propiedades(20, 1, 100));
    }
    @Test void ackSinReturnEsExitoConMensajePersistente() {
        doAnswer(call -> {
            Message msg = call.getArgument(2);
            assertThat(msg.getMessageProperties().getMessageId()).isEqualTo(evento.eventId().toString());
            assertThat(msg.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(msg.getMessageProperties().getCorrelationId()).isEqualTo(evento.correlationId().toString());
            ((CorrelationData) call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(eq("vuelos.events"), eq("vuelos.actualizados"), any(Message.class), any(CorrelationData.class));
        publicador.publicar(evento);
        verify(rabbit).setMandatory(true);
    }
    @Test void nackNoEsEntrega() {
        doAnswer(call -> {
            ((CorrelationData) call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThatThrownBy(() -> publicador.publicar(evento)).hasMessage("BROKER_NACK");
    }
    @Test void ackConReturnNoEsEntrega() {
        doAnswer(call -> {
            CorrelationData cd = call.getArgument(3);
            cd.setReturned(new ReturnedMessage(call.getArgument(2), 312, "NO_ROUTE", "vuelos.events", "vuelos.actualizados"));
            cd.getFuture().complete(new CorrelationData.Confirm(true, null)); return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThatThrownBy(() -> publicador.publicar(evento)).hasMessage("SIN_DESTINO");
    }
    @Test void timeoutConservaPendiente() {
        assertThatThrownBy(() -> publicador.publicar(evento)).hasMessage("CONFIRM_TIMEOUT");
    }
    @Test void desconexionEsTemporal() {
        doThrow(new AmqpConnectException(new ConnectException("privado"))).when(rabbit)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThatThrownBy(() -> publicador.publicar(evento)).hasMessage("BROKER_NO_DISPONIBLE");
    }
    @Test void configuracionSinReturnsNoPermiteMarcarEnviado() {
        when(factory.isPublisherReturns()).thenReturn(false);
        assertThatThrownBy(() -> publicador.publicar(evento)).hasMessage("CONFIRMS_RETURNS_NO_CONFIGURADOS");
        verify(rabbit, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidos")
    void datosInvalidosNoContactanBroker(String caso, VuelosOutboxRepository.Evento fila) {
        assertThatThrownBy(() -> publicador.publicar(fila)).as(caso).isInstanceOf(PublicadorVuelos.FalloDatos.class);
        verify(rabbit, never()).getConnectionFactory();
        verify(rabbit, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
    static VuelosOutboxRepository.Evento valido() {
        UUID id = UUID.randomUUID(), corr = UUID.randomUUID();
        String json = """
                {"eventId":"%s","correlationId":"%s","timestamp":"2026-11-07T21:00:00Z","tipo":"vuelos.actualizados",
                "payload":{"vueloId":1,"revision":1,"origen":"Quito","destino":"Loja","fechaSalida":"2027-05-10",
                "horaSalida":"09:30:00","precio":125.50,"estado":"ACTIVO","capacidadTotal":100}}
                """.formatted(id, corr);
        return new VuelosOutboxRepository.Evento(id, corr, 1, 1, json, 0);
    }
    static Stream<Arguments> invalidos() throws Exception {
        var base = valido(); var mapper = new ObjectMapper(); List<Arguments> casos = new ArrayList<>();
        for (String campo : List.of("eventId", "correlationId", "timestamp", "tipo", "payload", "payload.vueloId",
                "payload.revision", "payload.origen", "payload.destino", "payload.fechaSalida", "payload.horaSalida",
                "payload.precio", "payload.estado", "payload.capacidadTotal")) {
            for (int defecto = 0; defecto < 3; defecto++) {
                var json = (ObjectNode) mapper.readTree(base.mensaje());
                var padre = campo.startsWith("payload.") ? (ObjectNode) json.get("payload") : json;
                String clave = campo.startsWith("payload.") ? campo.substring(8) : campo;
                if (defecto == 0) padre.remove(clave); else if (defecto == 1) padre.putNull(clave); else padre.put(clave, false);
                casos.add(Arguments.of(campo + " defecto " + defecto, fila(base, json.toString())));
            }
        }
        for (String corrupto : List.of("{", "null", "[]", base.mensaje() + " {}",
                base.mensaje().replace("\"tipo\":", "\"tipo\":\"vuelos.actualizados\",\"tipo\":"))) {
            casos.add(Arguments.of("JSON inválido", fila(base, corrupto)));
        }
        for (String campo : List.of("eventId", "correlationId")) {
            var json = (ObjectNode) mapper.readTree(base.mensaje()); json.put(campo, UUID.randomUUID().toString());
            casos.add(Arguments.of(campo + " incoherente", fila(base, json.toString())));
        }
        String[][] valores = {{"vueloId", "2"}, {"revision", "2"}, {"revision", "0"}, {"precio", "0"},
                {"precio", "1.234"}, {"precio", "100000000"}, {"precio", "\"125.50\""}, {"origen", "\"Loja\""},
                {"horaSalida", "\"09:30:00Z\""}, {"fechaSalida", "\"2027-02-30\""}, {"estado", "\"DESCONOCIDO\""},
                {"capacidadTotal", "0"}, {"capacidadTotal", "1.5"}};
        for (var valor : valores) {
            var json = (ObjectNode) mapper.readTree(base.mensaje()); ((ObjectNode) json.get("payload")).set(valor[0], mapper.readTree(valor[1]));
            casos.add(Arguments.of("Valor inválido " + valor[0], fila(base, json.toString())));
        }
        var extra = (ObjectNode) mapper.readTree(base.mensaje()); ((ObjectNode) extra.get("payload")).put("capacidadDisponible", 80);
        casos.add(Arguments.of("Disponibilidad fuera del contrato", fila(base, extra.toString())));
        return casos.stream();
    }
    private static VuelosOutboxRepository.Evento fila(VuelosOutboxRepository.Evento base, String json) {
        return new VuelosOutboxRepository.Evento(base.eventId(), base.correlationId(), base.vueloId(), base.revision(), json, 0);
    }
}

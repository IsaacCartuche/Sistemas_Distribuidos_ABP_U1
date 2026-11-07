package com.empresa.inventario.outbox;

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
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RabbitPublicadorExpiracionTest {
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final CachingConnectionFactory factory = mock(CachingConnectionFactory.class);
    private final OutboxRepository.Evento evento = eventoValido();
    private RabbitPublicadorExpiracion publicador;

    @BeforeEach
    void preparar() {
        when(rabbit.getConnectionFactory()).thenReturn(factory);
        when(factory.isPublisherConfirms()).thenReturn(true);
        when(factory.isPublisherReturns()).thenReturn(true);
        publicador = new RabbitPublicadorExpiracion(rabbit, new OutboxConfig.Propiedades(20, 1, 100),
                new ValidadorExpiracion(new ObjectMapper()));
    }

    @Test
    void ackSinReturnConservaIdentidadesYPersistencia() {
        doAnswer(call -> {
            Message msg = call.getArgument(2);
            assertThat(msg.getMessageProperties().getMessageId()).isEqualTo(evento.eventId().toString());
            assertThat(msg.getMessageProperties().getCorrelationId()).isEqualTo(evento.correlationId().toString());
            assertThat(msg.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(msg.getMessageProperties().getContentType()).isEqualTo("application/json");
            ((CorrelationData) call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(eq("vuelos.events"), eq("asiento.expirado"), any(Message.class), any(CorrelationData.class));
        publicador.publicar(evento);
        verify(rabbit).setMandatory(true);
    }

    @Test
    void ackConReturnNoEsEntrega() {
        doAnswer(call -> {
            CorrelationData cd = call.getArgument(3);
            cd.setReturned(new ReturnedMessage(call.getArgument(2), 312, "NO_ROUTE", "vuelos.events", "asiento.expirado"));
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThatThrownBy(() -> publicador.publicar(evento)).hasMessage("SIN_DESTINO");
    }

    @Test
    void nackNoEsEntrega() {
        doAnswer(call -> {
            ((CorrelationData) call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThatThrownBy(() -> publicador.publicar(evento)).hasMessage("BROKER_NACK");
    }

    @Test
    void timeoutNoEsEntrega() {
        assertThatThrownBy(() -> publicador.publicar(evento)).hasMessage("CONFIRM_TIMEOUT");
    }

    @Test
    void desconexionSeClasificaSinExponerMensaje() {
        doThrow(new AmqpConnectException(new ConnectException("detalle privado"))).when(rabbit)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThatThrownBy(() -> publicador.publicar(evento)).hasMessage("BROKER_NO_DISPONIBLE");
    }

    @Test
    void sinConfirmsOReturnsNoPublica() {
        when(factory.isPublisherReturns()).thenReturn(false);
        assertThatThrownBy(() -> publicador.publicar(evento)).hasMessage("CONFIRMS_RETURNS_NO_CONFIGURADOS");
        verify(rabbit, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void sinContextoNoPublica() {
        assertThatThrownBy(() -> publicador.publicar(new OutboxRepository.Evento(evento.eventId(), null,
                evento.token(), evento.mensaje(), 0))).isInstanceOf(PublicadorExpiracion.FalloDatos.class);
        verify(rabbit, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mensajesInvalidos")
    void mensajeInvalidoSeRechazaAntesDeContactarBroker(String caso, OutboxRepository.Evento invalido) {
        assertThatThrownBy(() -> publicador.publicar(invalido)).as(caso)
                .isInstanceOf(PublicadorExpiracion.FalloDatos.class);
        verify(rabbit, never()).getConnectionFactory();
        verify(rabbit, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void reservaComoCadenaNoSeConvierteNiSeModificaElMensaje() throws Exception {
        ObjectNode json = (ObjectNode) new ObjectMapper().readTree(evento.mensaje());
        ((ObjectNode) json.get("payload")).put("reservaId", "reserva-123");
        var cadena = conMensaje(evento, json.toString());
        doAnswer(call -> {
            assertThat(((Message) call.getArgument(2)).getBody())
                    .isEqualTo(cadena.mensaje().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ((CorrelationData) call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publicador.publicar(cadena);
    }

    private static OutboxRepository.Evento eventoValido() {
        UUID id = UUID.randomUUID(), correlation = UUID.randomUUID(), token = UUID.randomUUID();
        String json = """
                {"eventId":"%s","correlationId":"%s","timestamp":"2026-11-07T21:00:00Z",
                 "tipo":"asiento.expirado","payload":{"reservaId":123,"vueloId":1,"asientoId":2,
                 "asiento":"12A","token":"%s"}}
                """.formatted(id, correlation, token);
        return new OutboxRepository.Evento(id, correlation, token, json, 0);
    }

    private static OutboxRepository.Evento conMensaje(OutboxRepository.Evento fila, String mensaje) {
        return new OutboxRepository.Evento(fila.eventId(), fila.correlationId(), fila.token(), mensaje, 0);
    }

    static Stream<Arguments> mensajesInvalidos() throws Exception {
        var fila = eventoValido();
        ObjectMapper mapper = new ObjectMapper();
        List<Arguments> casos = new ArrayList<>();
        String[] campos = {"eventId", "correlationId", "timestamp", "tipo", "payload.reservaId",
                "payload.vueloId", "payload.asientoId", "payload.asiento", "payload.token"};
        for (String campo : campos) {
            for (String defecto : List.of("ausente", "nulo", "tipo incorrecto")) {
                ObjectNode json = (ObjectNode) mapper.readTree(fila.mensaje());
                boolean payload = campo.startsWith("payload.");
                ObjectNode padre = payload ? (ObjectNode) json.get("payload") : json;
                String clave = payload ? campo.substring("payload.".length()) : campo;
                if (defecto.equals("ausente")) padre.remove(clave);
                else if (defecto.equals("nulo")) padre.putNull(clave);
                else padre.put(clave, false);
                casos.add(Arguments.of(campo + " " + defecto, conMensaje(fila, json.toString())));
            }
        }
        for (String corrupto : List.of("{", "null", "[]", "\"texto\"", "", fila.mensaje() + " {}",
                fila.mensaje().replace("\"tipo\":", "\"tipo\":\"asiento.expirado\",\"tipo\":"))) {
            casos.add(Arguments.of("JSON corrupto/ambiguo: " + corrupto, conMensaje(fila, corrupto)));
        }
        for (String campo : List.of("eventId", "correlationId", "token")) {
            ObjectNode json = (ObjectNode) mapper.readTree(fila.mensaje());
            (campo.equals("token") ? (ObjectNode) json.get("payload") : json).put(campo, UUID.randomUUID().toString());
            casos.add(Arguments.of(campo + " no coincide con fila", conMensaje(fila, json.toString())));
        }
        String[][] valores = {{"tipo", "\"asiento.liberado\""}, {"timestamp", "\"2026-11-07T21:00:00\""},
                {"timestamp", "\"fecha-invalida\""}, {"eventId", "\"1-1-1-1-1\""},
                {"payload.vueloId", "0"}, {"payload.asientoId", "-1"}, {"payload.vueloId", "2147483648"},
                {"payload.asientoId", "1.5"}, {"payload.vueloId", "\"1\""}, {"payload.asiento", "\"\""},
                {"payload.asiento", "\"1 A\""}, {"payload.asiento", "\"ABCDEFGHIJK\""},
                {"payload.token", "\"no-uuid\""}, {"payload.reservaId", "\" \""}, {"payload.reservaId", "123.5"},
                {"payload", "null"}, {"payload", "[]"}, {"payload", "\"objeto\""}};
        for (String[] valor : valores) {
            ObjectNode json = (ObjectNode) mapper.readTree(fila.mensaje());
            boolean payload = valor[0].startsWith("payload.");
            ObjectNode padre = payload ? (ObjectNode) json.get("payload") : json;
            padre.set(payload ? valor[0].substring("payload.".length()) : valor[0], mapper.readTree(valor[1]));
            casos.add(Arguments.of(valor[0] + " valor inválido " + valor[1], conMensaje(fila, json.toString())));
        }
        ObjectNode sinPayload = (ObjectNode) mapper.readTree(fila.mensaje());
        sinPayload.remove("payload");
        casos.add(Arguments.of("payload ausente", conMensaje(fila, sinPayload.toString())));
        casos.add(Arguments.of("eventId de fila ausente", new OutboxRepository.Evento(null, fila.correlationId(), fila.token(), fila.mensaje(), 0)));
        casos.add(Arguments.of("token de fila ausente", new OutboxRepository.Evento(fila.eventId(), fila.correlationId(), null, fila.mensaje(), 0)));
        casos.add(Arguments.of("mensaje nulo", conMensaje(fila, null)));
        return casos.stream();
    }
}

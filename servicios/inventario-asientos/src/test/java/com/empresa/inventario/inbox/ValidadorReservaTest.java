package com.empresa.inventario.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class ValidadorReservaTest {
    private final ValidadorReserva validador = new ValidadorReserva(new ObjectMapper());
    static String valido() {
        return """
                {"eventId":"10000000-0000-0000-0000-000000000001",
                 "correlationId":"20000000-0000-0000-0000-000000000001",
                 "timestamp":"2026-11-07T21:00:00Z","tipo":"reserva.creada",
                 "payload":{"reservaId":123,"vueloId":1,"asiento":"12B",
                 "token":"30000000-0000-0000-0000-000000000001"}}
                """;
    }
    @Test
    void conservaIdentidadesDistintasYTipoReserva() {
        EventoReserva evento = validador.validar(valido().getBytes(StandardCharsets.UTF_8), "reserva.creada");
        assertThat(evento.eventId()).isNotEqualTo(evento.correlationId());
        assertThat(evento.token()).isNotEqualTo(evento.correlationId());
        assertThat(evento.claveReserva()).isEqualTo("123");
        assertThat(evento.asiento()).isEqualTo("12B");
    }
    @Test
    void cancelacionYReservaComoCadenaSonExplicitas() {
        String json = valido().replace("reserva.creada", "reserva.cancelada").replace("\"reservaId\":123", "\"reservaId\":\"123\"");
        assertThat(validador.validar(json.getBytes(StandardCharsets.UTF_8), "reserva.cancelada").claveReserva()).isEqualTo("\"123\"");
    }
    @ParameterizedTest
    @MethodSource("invalidos")
    void rechazaCamposAusentesNulosOTiposIncorrectos(String caso, String mensaje) {
        assertThatThrownBy(() -> validador.validar(mensaje.getBytes(StandardCharsets.UTF_8), "reserva.creada"))
                .as(caso).isInstanceOf(EventoReservaInvalido.class);
    }
    static Stream<Arguments> invalidos() throws Exception {
        List<Arguments> casos = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        for (String campo : List.of("eventId", "correlationId", "timestamp", "tipo", "payload", "payload.reservaId",
                "payload.vueloId", "payload.asiento", "payload.token")) {
            for (int defecto = 0; defecto < 3; defecto++) {
                var json = (ObjectNode) mapper.readTree(valido());
                var padre = campo.startsWith("payload.") ? (ObjectNode) json.get("payload") : json;
                String clave = campo.startsWith("payload.") ? campo.substring(8) : campo;
                if (defecto == 0) padre.remove(clave);
                else if (defecto == 1) padre.putNull(clave);
                else padre.put(clave, true);
                casos.add(Arguments.of(campo + " defecto " + defecto, json.toString()));
            }
        }
        for (String mensaje : List.of("{", "null", "[]", valido() + " {}", valido().replace("123", "0"),
                valido().replace("\"vueloId\":1", "\"vueloId\":\"1\""), valido().replace("12B", "1 B"),
                valido().replace("T21:00:00Z", "T21:00:00"), valido().replace("reserva.creada", "reserva.confirmada"),
                valido().replace("\"tipo\":", "\"tipo\":\"reserva.creada\",\"tipo\":"))) {
            casos.add(Arguments.of("Mensaje inválido", mensaje));
        }
        return casos.stream();
    }
    @Test
    void routingDistintaNoAutorizaOperacion() {
        assertThatThrownBy(() -> validador.validar(valido().getBytes(StandardCharsets.UTF_8), "reserva.cancelada"))
                .hasMessage("TIPO_ROUTING_INCOHERENTE");
    }
    @Test
    void confirmacionExigeIdentidadCompletaDelAsiento() throws Exception {
        var json = (ObjectNode) new ObjectMapper().readTree(valido());
        json.put("tipo", "asiento.confirmacion_solicitada");
        ((ObjectNode) json.get("payload")).put("asientoId", 2);
        assertThat(validador.validar(json.toString().getBytes(StandardCharsets.UTF_8), "asiento.confirmacion_solicitada").asientoId()).isEqualTo(2);
    }
    @ParameterizedTest
    @ValueSource(strings = {"ausente", "null", "0", "-1", "1.5", "\"2\"", "true", "2147483648"})
    void confirmacionSinIdValidoEsMensajeInvalido(String valor) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var json = (ObjectNode) mapper.readTree(valido());
        json.put("tipo", "asiento.confirmacion_solicitada");
        if (!valor.equals("ausente")) ((ObjectNode) json.get("payload")).set("asientoId", mapper.readTree(valor));
        assertThatThrownBy(() -> validador.validar(json.toString().getBytes(StandardCharsets.UTF_8), "asiento.confirmacion_solicitada"))
                .hasMessage("ASIENTO_ID_INVALIDO");
    }
}

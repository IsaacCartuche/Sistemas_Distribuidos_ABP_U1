package com.empresa.inventario.inbox;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.empresa.inventario.outbox.ValidadorExpiracion;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@Component
public class ValidadorReserva {
    private final ObjectReader reader;
    public ValidadorReserva(ObjectMapper mapper) {
        reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
    public EventoReserva validar(byte[] mensaje, String routingKey) {
        if (mensaje == null || mensaje.length == 0) throw error("JSON_INVALIDO");
        JsonNode json;
        try { json = reader.readTree(mensaje); }
        catch (java.io.IOException exception) { throw error("JSON_INVALIDO"); }
        if (json == null || !json.isObject()) throw error("SOBRE_INVALIDO");
        String tipo = texto(json.get("tipo"), "tipo");
        if (!(tipo.equals("reserva.creada") || tipo.equals("reserva.cancelada")) || !tipo.equals(routingKey)) {
            throw error("TIPO_ROUTING_INCOHERENTE");
        }
        Instant timestamp;
        try { timestamp = Instant.parse(texto(json.get("timestamp"), "timestamp")); }
        catch (DateTimeParseException exception) { throw error("TIMESTAMP_INVALIDO"); }
        JsonNode payload = json.get("payload");
        if (payload == null || !payload.isObject()) throw error("PAYLOAD_INVALIDO");
        JsonNode reserva = payload.get("reservaId");
        if (!ValidadorExpiracion.reservaValida(reserva)
                || (reserva.isIntegralNumber() && reserva.bigIntegerValue().signum() <= 0)) {
            throw error("RESERVA_ID_INVALIDO");
        }
        JsonNode vuelo = payload.get("vueloId");
        if (vuelo == null || !vuelo.isIntegralNumber() || !vuelo.canConvertToInt() || vuelo.intValue() <= 0) {
            throw error("VUELO_ID_INVALIDO");
        }
        String asiento = texto(payload.get("asiento"), "asiento");
        if (!asiento.matches("[A-Za-z0-9-]{1,10}")) throw error("ASIENTO_INVALIDO");
        return new EventoReserva(uuid(json.get("eventId"), "eventId"), uuid(json.get("correlationId"), "correlationId"),
                timestamp, tipo, reserva.deepCopy(), vuelo.intValue(), asiento, uuid(payload.get("token"), "token"));
    }
    private String texto(JsonNode nodo, String campo) {
        if (nodo == null || !nodo.isTextual() || nodo.asText().isBlank()) throw error("CAMPO_INVALIDO:" + campo);
        return nodo.asText();
    }
    private UUID uuid(JsonNode nodo, String campo) {
        String valor = texto(nodo, campo);
        if (!valor.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) {
            throw error("UUID_INVALIDO:" + campo);
        }
        return UUID.fromString(valor);
    }
    private EventoReservaInvalido error(String codigo) { return new EventoReservaInvalido(codigo); }
}

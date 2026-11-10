package com.empresa.vuelos.outbox;

import com.empresa.vuelos.domain.EstadoVuelo;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;

@Component
public class ValidadorEventoVuelo {
    private static final Set<String> SOBRE = Set.of("eventId", "correlationId", "timestamp", "tipo", "payload");
    private static final Set<String> PAYLOAD = Set.of("vueloId", "revision", "origen", "destino", "fechaSalida", "horaSalida",
            "precio", "estado", "capacidadTotal");
    private final ObjectReader reader;
    public ValidadorEventoVuelo(ObjectMapper mapper) {
        reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
    public void validar(VuelosOutboxRepository.Evento fila) {
        if (fila == null || fila.mensaje() == null || fila.mensaje().isBlank()) throw fallo("JSON_INVALIDO");
        JsonNode evento;
        try { evento = reader.readTree(fila.mensaje()); }
        catch (JsonProcessingException exception) { throw fallo("JSON_INVALIDO"); }
        campos(evento, SOBRE);
        if (!uuid(evento.get("eventId")).equals(fila.eventId())) throw fallo("EVENT_ID_INCOHERENTE");
        if (!uuid(evento.get("correlationId")).equals(fila.correlationId())) throw fallo("CORRELATION_ID_INCOHERENTE");
        if (!"vuelos.actualizados".equals(texto(evento.get("tipo")))) throw fallo("TIPO_INVALIDO");
        try {
            String timestamp = texto(evento.get("timestamp"));
            Instant.parse(timestamp);
            if (!timestamp.endsWith("Z")) throw fallo("TIMESTAMP_NO_UTC");
        } catch (DateTimeParseException exception) { throw fallo("TIMESTAMP_INVALIDO"); }
        JsonNode payload = evento.get("payload");
        campos(payload, PAYLOAD);
        if (!Integer.valueOf(positivo(payload.get("vueloId"))).equals(fila.vueloId())) throw fallo("VUELO_ID_INCOHERENTE");
        if (!Integer.valueOf(positivo(payload.get("revision"))).equals(fila.revision())) throw fallo("REVISION_INCOHERENTE");
        String origen = texto(payload.get("origen")), destino = texto(payload.get("destino"));
        if (origen.length() > 100 || destino.length() > 100 || origen.strip().equalsIgnoreCase(destino.strip())) throw fallo("RUTA_INVALIDA");
        try {
            LocalDate.parse(texto(payload.get("fechaSalida")));
            LocalTime.parse(texto(payload.get("horaSalida"))); // Horario local, sin offset ni zona inventada.
        } catch (DateTimeParseException exception) { throw fallo("HORARIO_INVALIDO"); }
        JsonNode precio = payload.get("precio");
        if (precio == null || !precio.isNumber()) throw fallo("PRECIO_INVALIDO");
        var decimal = precio.decimalValue();
        if (decimal.signum() <= 0 || Math.max(0, decimal.scale()) > 2 || decimal.precision() - decimal.scale() > 8) throw fallo("PRECIO_INVALIDO");
        try { EstadoVuelo.valueOf(texto(payload.get("estado"))); }
        catch (IllegalArgumentException exception) { throw fallo("ESTADO_INVALIDO"); }
        positivo(payload.get("capacidadTotal"));
    }
    private void campos(JsonNode nodo, Set<String> permitidos) {
        if (nodo == null || !nodo.isObject() || nodo.size() != permitidos.size()) throw fallo("CAMPOS_INVALIDOS");
        nodo.fieldNames().forEachRemaining(campo -> { if (!permitidos.contains(campo)) throw fallo("CAMPO_NO_PERMITIDO"); });
    }
    private String texto(JsonNode nodo) {
        if (nodo == null || !nodo.isTextual() || nodo.asText().isBlank()) throw fallo("TEXTO_INVALIDO");
        return nodo.asText();
    }
    private int positivo(JsonNode nodo) {
        if (nodo == null || !nodo.isIntegralNumber() || !nodo.canConvertToInt() || nodo.intValue() <= 0) throw fallo("ENTERO_INVALIDO");
        return nodo.intValue();
    }
    private UUID uuid(JsonNode nodo) {
        String valor = texto(nodo);
        if (!valor.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) throw fallo("UUID_INVALIDO");
        return UUID.fromString(valor);
    }
    private PublicadorVuelos.FalloDatos fallo(String codigo) { return new PublicadorVuelos.FalloDatos(codigo); }
}

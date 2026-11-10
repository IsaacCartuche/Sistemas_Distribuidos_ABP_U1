package com.empresa.inventario.outbox;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.Set;
import java.util.regex.Pattern;

/** Valida sin normalizar, reparar ni sustituir valores del mensaje persistido. */
@Component
public class ValidadorExpiracion {
    public static final Set<String> TIPOS = Set.of("asiento.expirado", "asiento.bloqueado", "asiento.no_disponible",
            "asiento.confirmado", "asiento.confirmacion_rechazada");
    public static final Set<String> MOTIVOS = Set.of("ASIENTO_INEXISTENTE", "ASIENTO_OCUPADO", "TOKEN_DE_OTRA_RESERVA",
            "TOKEN_INVALIDO", "RETENCION_TERMINAL", "SOLICITUD_INVALIDA", "RESERVA_NO_COINCIDE", "RESERVA_CANCELADA",
            "SOLICITUD_RECHAZADA", "RETENCION_INEXISTENTE", "RETENCION_EXPIRADA", "ASIENTO_NO_DISPONIBLE");
    private static final Pattern UUID_CANONICO = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private final ObjectReader reader;

    public ValidadorExpiracion(ObjectMapper mapper) {
        reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public void validar(OutboxRepository.Evento fila) {
        ObjectNode evento = validarBase(fila);
        if (!uuid(evento.get("correlationId"), "correlationId").equals(fila.correlationId())) {
            throw invalido("CORRELATION_ID_INCOHERENTE");
        }
        if (!reservaValida(evento.path("payload").get("reservaId"))) throw invalido("RESERVA_ID_INVALIDO");
        if (fila.tipo().equals("asiento.no_disponible") || fila.tipo().equals("asiento.confirmacion_rechazada")) {
            String motivo = texto(evento.path("payload").get("motivo"), "payload.motivo");
            if (!MOTIVOS.contains(motivo)) throw invalido("MOTIVO_INVALIDO");
        }
    }

    /** Solo el contexto ausente previsto puede completarse: no repara contexto parcialmente corrupto. */
    public ObjectNode validarEsperaContexto(OutboxRepository.Evento fila) {
        ObjectNode evento = validarBase(fila);
        if (!fila.tipo().equals("asiento.expirado") || fila.correlationId() != null
                || !evento.has("correlationId") || !evento.get("correlationId").isNull()
                || !evento.path("payload").has("reservaId") || !evento.path("payload").get("reservaId").isNull()) {
            throw invalido("CONTEXTO_INICIAL_INCOHERENTE");
        }
        return evento;
    }

    public static boolean reservaValida(JsonNode valor) {
        // El contrato actual admite entero o cadena no vacía; no se deduce ni transforma su identidad.
        return valor != null && (valor.isIntegralNumber() || (valor.isTextual() && !valor.asText().isBlank()));
    }

    private ObjectNode validarBase(OutboxRepository.Evento fila) {
        if (fila == null || fila.eventId() == null || fila.token() == null) throw invalido("FILA_INCOMPLETA");
        if (fila.mensaje() == null || fila.mensaje().isBlank()) throw invalido("JSON_INVALIDO");
        JsonNode raiz;
        try {
            raiz = reader.readTree(fila.mensaje());
        } catch (JsonProcessingException exception) {
            throw invalido("JSON_INVALIDO"); // No conservar cuerpos, tokens ni mensajes del parser en logs.
        }
        if (raiz == null || !raiz.isObject()) throw invalido("RAIZ_INVALIDA");
        ObjectNode evento = (ObjectNode) raiz;
        if (!uuid(evento.get("eventId"), "eventId").equals(fila.eventId())) throw invalido("EVENT_ID_INCOHERENTE");
        if (!TIPOS.contains(texto(evento.get("tipo"), "tipo")) || !evento.get("tipo").asText().equals(fila.tipo())) {
            throw invalido("TIPO_INVALIDO");
        }
        try {
            Instant.parse(texto(evento.get("timestamp"), "timestamp"));
        } catch (DateTimeParseException exception) {
            throw invalido("TIMESTAMP_INVALIDO");
        }
        JsonNode payload = evento.get("payload");
        if (payload == null || !payload.isObject()) throw invalido("PAYLOAD_INVALIDO");
        enteroPositivo(payload.get("vueloId"), "payload.vueloId");
        // Un rechazo de bloqueo puede referirse a un asiento que NO existe. No se inventa un ID.
        if (!(fila.tipo().equals("asiento.no_disponible") && payload.has("asientoId") && payload.get("asientoId").isNull())) {
            enteroPositivo(payload.get("asientoId"), "payload.asientoId");
        }
        if (!texto(payload.get("asiento"), "payload.asiento").matches("[A-Za-z0-9-]{1,10}")) {
            throw invalido("ASIENTO_INVALIDO");
        }
        if (!uuid(payload.get("token"), "payload.token").equals(fila.token())) throw invalido("TOKEN_INCOHERENTE");
        return evento;
    }

    private String texto(JsonNode valor, String campo) {
        if (valor == null || !valor.isTextual() || valor.asText().isBlank()) throw invalido("CAMPO_INVALIDO:" + campo);
        return valor.asText();
    }
    private UUID uuid(JsonNode valor, String campo) {
        String texto = texto(valor, campo);
        if (!UUID_CANONICO.matcher(texto).matches()) throw invalido("UUID_INVALIDO:" + campo);
        return UUID.fromString(texto);
    }
    private void enteroPositivo(JsonNode valor, String campo) {
        if (valor == null || !valor.isIntegralNumber() || !valor.canConvertToInt() || valor.intValue() <= 0) {
            throw invalido("CAMPO_INVALIDO:" + campo);
        }
    }
    private PublicadorExpiracion.FalloDatos invalido(String motivo) {
        return new PublicadorExpiracion.FalloDatos(motivo);
    }
}

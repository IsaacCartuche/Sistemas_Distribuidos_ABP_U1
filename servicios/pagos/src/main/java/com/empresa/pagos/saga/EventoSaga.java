package com.empresa.pagos.saga;
import java.time.OffsetDateTime;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
public record EventoSaga(UUID eventId, UUID correlationId, String timestamp, JsonNode payload) {
 public void validar() {
  if(eventId==null || correlationId==null || timestamp==null || payload==null || !payload.isObject())
   throw new IllegalArgumentException("Sobre de evento incompleto");
  // Acepta el timestamp local del contrato existente y timestamps con offset.
  try { OffsetDateTime.parse(timestamp); } catch(java.time.format.DateTimeParseException e) { java.time.LocalDateTime.parse(timestamp); }
  if(!payload.path("reservaId").isIntegralNumber() || !payload.path("reservaId").canConvertToInt() || payload.path("reservaId").asInt()<=0)
   throw new IllegalArgumentException("reservaId requerido");
 }
}

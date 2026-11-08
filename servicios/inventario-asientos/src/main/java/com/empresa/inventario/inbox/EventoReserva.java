package com.empresa.inventario.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** No equipara identificador de reserva, correlación y token. */
public record EventoReserva(UUID eventId, UUID correlationId, Instant timestamp, String tipo,
                            JsonNode reservaId, Integer vueloId, String asiento, UUID token, Integer asientoId) {
    public String claveReserva() { return reservaId.toString(); }
}

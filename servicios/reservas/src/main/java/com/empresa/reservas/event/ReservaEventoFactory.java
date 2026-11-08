package com.empresa.reservas.event;

import com.empresa.reservas.dto.ReservaCreadaEvento;
import com.empresa.reservas.entity.OutboxEvento;
import com.empresa.reservas.entity.Reserva;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
public class ReservaEventoFactory {

    private final ObjectMapper objectMapper;

    public ReservaEventoFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OutboxEvento crearEventoReservaCreada(Reserva reserva) {

        UUID eventId = UUID.randomUUID();

        ReservaCreadaEvento mensaje = new ReservaCreadaEvento(
                eventId,
                reserva.getCorrelationId(),
                LocalDateTime.now(),
                "reserva.creada",
                new ReservaCreadaEvento.Payload(
                        reserva.getId(),
                        reserva.getVueloId(),
                        reserva.getAsiento(),
                        reserva.getToken(),
                        reserva.getAsientoId()
                )
        );

        try {
            OutboxEvento evento = new OutboxEvento();

            evento.setId(eventId);
            evento.setCorrelationId(reserva.getCorrelationId());
            evento.setTipo("reserva.creada");
            evento.setExchange("vuelos.events");
            evento.setRoutingKey("reserva.creada");
            evento.setPayload(objectMapper.writeValueAsString(mensaje));

            return evento;

        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "No se pudo serializar el evento reserva.creada", ex
            );
        }
    }
}

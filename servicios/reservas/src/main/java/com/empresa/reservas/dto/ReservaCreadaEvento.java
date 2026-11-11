package com.empresa.reservas.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record ReservaCreadaEvento(
        UUID eventId,
        UUID correlationId,
        LocalDateTime timestamp,
        String tipo,
        Payload payload
) {

    public record Payload(
            Integer reservaId,
            Integer vueloId,
            String asiento,
            UUID token,
            Integer asientoId,
            java.math.BigDecimal total
    ) {
        public Payload(Integer reservaId,Integer vueloId,String asiento,UUID token,Integer asientoId) {this(reservaId,vueloId,asiento,token,asientoId,null);}
    }
}

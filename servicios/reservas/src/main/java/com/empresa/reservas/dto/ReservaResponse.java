package com.empresa.reservas.dto;

import com.empresa.reservas.entity.Reserva;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ReservaResponse(
        Integer id,
        Integer vueloId,
        Integer usuarioId,
        String asiento,
        String estado,
        LocalDateTime fechaCreacion,
        LocalDateTime fechaExpiracion,
        BigDecimal total
) {

    public static ReservaResponse desdeEntidad(Reserva reserva) {
        return new ReservaResponse(
                reserva.getId(),
                reserva.getVueloId(),
                reserva.getUsuarioId(),
                reserva.getAsiento(),
                reserva.getEstado(),
                reserva.getFechaCreacion(),
                reserva.getFechaExpiracion(),
                reserva.getTotal()
        );
    }
}

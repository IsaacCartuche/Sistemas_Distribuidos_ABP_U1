package com.empresa.reservas.dto;

import java.math.BigDecimal;

public record CrearReservaRequest(
        Integer vueloId,
        Integer usuarioId,
        String asiento,
        BigDecimal total,
        String idempotencyKey
) {
}

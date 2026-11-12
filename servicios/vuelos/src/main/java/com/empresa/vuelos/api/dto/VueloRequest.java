package com.empresa.vuelos.api.dto;

import com.empresa.vuelos.domain.EstadoVuelo;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record VueloRequest(
        @NotBlank @Size(max = 100) String origen,
        @NotBlank @Size(max = 100) String destino,
        @NotNull LocalDate fechaSalida,
        @NotNull LocalTime horaSalida,
        @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal precio,
        @NotNull EstadoVuelo estado,
        @NotNull @Positive Integer capacidadTotal,
        @NotNull @PositiveOrZero Integer capacidadDisponible
) {
}

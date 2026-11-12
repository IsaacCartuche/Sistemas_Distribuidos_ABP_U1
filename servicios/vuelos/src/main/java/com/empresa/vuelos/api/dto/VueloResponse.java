package com.empresa.vuelos.api.dto;

import com.empresa.vuelos.domain.EstadoVuelo;
import com.empresa.vuelos.domain.Vuelo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record VueloResponse(
        Integer id,
        String origen,
        String destino,
        LocalDate fechaSalida,
        LocalTime horaSalida,
        BigDecimal precio,
        EstadoVuelo estado,
        Integer capacidadTotal,
        Integer capacidadDisponible,
        LocalDateTime fechaCreacion
) {
    public static VueloResponse from(Vuelo vuelo) {
        return new VueloResponse(vuelo.getId(), vuelo.getOrigen(), vuelo.getDestino(),
                vuelo.getFechaSalida(), vuelo.getHoraSalida(), vuelo.getPrecio(), vuelo.getEstado(),
                vuelo.getCapacidadTotal(), vuelo.getCapacidadDisponible(), vuelo.getFechaCreacion());
    }
}

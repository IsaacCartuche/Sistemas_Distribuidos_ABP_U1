package com.empresa.vuelos.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "vuelos")
public class Vuelo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 100)
    private String origen;

    @Column(nullable = false, length = 100)
    private String destino;

    @Column(name = "fecha_salida", nullable = false)
    private LocalDate fechaSalida;

    @Column(name = "hora_salida", nullable = false)
    private LocalTime horaSalida;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal precio;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private EstadoVuelo estado;

    @Column(name = "capacidad_total", nullable = false)
    private Integer capacidadTotal;

    // Dato informativo del catálogo: no representa disponibilidad real de inventario.
    @Column(name = "capacidad_disponible", nullable = false)
    private Integer capacidadDisponible;

    @Column(name = "fecha_creacion", updatable = false)
    private LocalDateTime fechaCreacion;

    protected Vuelo() {
    }

    public Vuelo(String origen, String destino, LocalDate fechaSalida, LocalTime horaSalida,
                 BigDecimal precio, EstadoVuelo estado, Integer capacidadTotal, Integer capacidadDisponible) {
        actualizar(origen, destino, fechaSalida, horaSalida, precio, estado, capacidadTotal, capacidadDisponible);
    }

    public void actualizar(String origen, String destino, LocalDate fechaSalida, LocalTime horaSalida,
                           BigDecimal precio, EstadoVuelo estado, Integer capacidadTotal, Integer capacidadDisponible) {
        this.origen = origen;
        this.destino = destino;
        this.fechaSalida = fechaSalida;
        this.horaSalida = horaSalida;
        this.precio = precio;
        this.estado = estado;
        this.capacidadTotal = capacidadTotal;
        this.capacidadDisponible = capacidadDisponible;
    }

    @PrePersist
    private void asignarFechaCreacion() {
        fechaCreacion = LocalDateTime.now();
    }

    public Integer getId() { return id; }
    public String getOrigen() { return origen; }
    public String getDestino() { return destino; }
    public LocalDate getFechaSalida() { return fechaSalida; }
    public LocalTime getHoraSalida() { return horaSalida; }
    public BigDecimal getPrecio() { return precio; }
    public EstadoVuelo getEstado() { return estado; }
    public Integer getCapacidadTotal() { return capacidadTotal; }
    public Integer getCapacidadDisponible() { return capacidadDisponible; }
    public LocalDateTime getFechaCreacion() { return fechaCreacion; }
}

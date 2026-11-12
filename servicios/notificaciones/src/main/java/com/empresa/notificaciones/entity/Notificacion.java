package com.empresa.notificaciones.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Entidad de notificación persistida en la base de datos propia del servicio.
 * Cada servicio tiene su propia BD; este servicio usa db_notificaciones.
 */
@Entity
@Table(name = "notificaciones")
public class Notificacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usuario_id", nullable = false)
    private Integer usuarioId;

    @Column(nullable = false)
    private String tipo;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String mensaje;

    @Column(name = "fecha_envio", nullable = false)
    private LocalDateTime fechaEnvio;

    @Column(nullable = false)
    private boolean leida = false;

    protected Notificacion() {}

    public Notificacion(Integer usuarioId, String tipo, String mensaje, LocalDateTime fechaEnvio) {
        this.usuarioId = usuarioId;
        this.tipo = tipo;
        this.mensaje = mensaje;
        this.fechaEnvio = fechaEnvio;
    }

    public Long getId() { return id; }
    public Integer getUsuarioId() { return usuarioId; }
    public String getTipo() { return tipo; }
    public String getMensaje() { return mensaje; }
    public LocalDateTime getFechaEnvio() { return fechaEnvio; }
    public boolean isLeida() { return leida; }

    public void setLeida(boolean leida) { this.leida = leida; }
}

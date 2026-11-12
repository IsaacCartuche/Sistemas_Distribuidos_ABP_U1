package com.empresa.inventario.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "retenciones")
public class Retencion {
    @Id
    private UUID token;
    @Column(name = "asiento_id", nullable = false)
    private Integer asientoId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private EstadoRetencion estado = EstadoRetencion.ACTIVA;
    @Column(name = "expira_en", nullable = false)
    private LocalDateTime expiraEn;
    @Version @Column(nullable = false)
    private Integer version;

    protected Retencion() { }
    public Retencion(UUID token, Integer asientoId, LocalDateTime expiraEn) {
        this.token = token;
        this.asientoId = asientoId;
        this.expiraEn = expiraEn;
    }
    public void terminar(EstadoRetencion estado) { this.estado = estado; }
    public UUID getToken() { return token; }
    public Integer getAsientoId() { return asientoId; }
    public EstadoRetencion getEstado() { return estado; }
    public LocalDateTime getExpiraEn() { return expiraEn; }
}

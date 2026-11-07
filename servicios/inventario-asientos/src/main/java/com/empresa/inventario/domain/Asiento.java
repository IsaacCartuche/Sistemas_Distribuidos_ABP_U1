package com.empresa.inventario.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "asientos", uniqueConstraints = @UniqueConstraint(columnNames = {"vuelo_id", "numero_asiento"}))
public class Asiento {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @Column(name = "vuelo_id", nullable = false)
    private Integer vueloId;
    @Column(name = "numero_asiento", nullable = false, length = 10)
    private String numeroAsiento;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private EstadoAsiento estado = EstadoAsiento.DISPONIBLE;
    @Column(name = "bloqueado_hasta")
    private LocalDateTime bloqueadoHasta;
    @Column(name = "bloqueo_token")
    private UUID bloqueoToken;
    @Version @Column(nullable = false)
    private Integer version;

    protected Asiento() { }
    public Asiento(Integer vueloId, String numeroAsiento) {
        this.vueloId = vueloId;
        this.numeroAsiento = numeroAsiento;
    }
    public boolean expirado(LocalDateTime ahora) {
        return estado == EstadoAsiento.BLOQUEADO && !bloqueadoHasta.isAfter(ahora);
    }
    public boolean disponible(LocalDateTime ahora) {
        return estado == EstadoAsiento.DISPONIBLE || expirado(ahora);
    }
    public void bloquear(UUID token, LocalDateTime hasta) {
        estado = EstadoAsiento.BLOQUEADO;
        bloqueoToken = token;
        bloqueadoHasta = hasta;
    }
    public void confirmar() {
        estado = EstadoAsiento.RESERVADO;
        bloqueadoHasta = null;
    }
    public void liberar() {
        estado = EstadoAsiento.DISPONIBLE;
        bloqueadoHasta = null;
        bloqueoToken = null;
    }
    public Integer getId() { return id; }
    public Integer getVueloId() { return vueloId; }
    public String getNumeroAsiento() { return numeroAsiento; }
    public EstadoAsiento getEstado() { return estado; }
    public LocalDateTime getBloqueadoHasta() { return bloqueadoHasta; }
    public UUID getBloqueoToken() { return bloqueoToken; }
    public Integer getVersion() { return version; }
}

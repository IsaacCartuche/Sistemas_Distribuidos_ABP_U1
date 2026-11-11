package com.empresa.pagos.entity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
@Entity @Table(name="pago_saga")
public class Pago {
 @Id private UUID correlationId;
 @Column(nullable=false,unique=true) private Integer reservaId;
 private BigDecimal monto;
 @Column(nullable=false) private String estado;
 @Column(nullable=false) private boolean creado;
 @Column(nullable=false) private boolean bloqueado;
 @Column(nullable=false) private boolean cancelado;
 private UUID token;
 @Version private Integer version;
 protected Pago(){}
 public UUID getCorrelationId(){return correlationId;}
 public Integer getReservaId(){return reservaId;}
 public BigDecimal getMonto(){return monto;}
 public String getEstado(){return estado;}
}

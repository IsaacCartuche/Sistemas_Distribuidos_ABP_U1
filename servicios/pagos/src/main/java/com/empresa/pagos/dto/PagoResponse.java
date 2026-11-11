package com.empresa.pagos.dto;

import com.empresa.pagos.model.Pago;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PagoResponse {
    private Long id;
    private Long reservaId;
    private BigDecimal monto;
    private String metodoPago;
    private String estado;
    private String transaccionExterna;
    private LocalDateTime fechaProcesamiento;

    public static PagoResponse desdeEntidad(Pago pago) {
        return PagoResponse.builder()
                .id(pago.getId())
                .reservaId(pago.getReservaId())
                .monto(pago.getMonto())
                .metodoPago(pago.getMetodoPago())
                .estado(pago.getEstado())
                .transaccionExterna(pago.getTransaccionExterna())
                .fechaProcesamiento(pago.getFechaProcesamiento())
                .build();
    }
}
package com.empresa.inventario.api;

import com.empresa.inventario.domain.*;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class InventarioDto {
    private InventarioDto() { }

    public record InicializarRequest(
            @NotEmpty @Size(max = 500) List<@NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,10}") String> numeros) { }

    public record AsientoResponse(Integer id, Integer vueloId, String numeroAsiento, EstadoAsiento estado,
                                  boolean disponible, LocalDateTime bloqueadoHasta, Integer version) {
        public static AsientoResponse from(Asiento asiento, LocalDateTime ahora) {
            return new AsientoResponse(asiento.getId(), asiento.getVueloId(), asiento.getNumeroAsiento(),
                    asiento.getEstado(), asiento.disponible(ahora), asiento.getBloqueadoHasta(), asiento.getVersion());
        }
    }
    public record RetencionResponse(UUID token, Integer asientoId, EstadoRetencion estado,
                                     LocalDateTime expiraEn) {
        public static RetencionResponse from(Retencion retencion) {
            return new RetencionResponse(retencion.getToken(), retencion.getAsientoId(),
                    retencion.getEstado(), retencion.getExpiraEn());
        }
    }
}

package com.empresa.inventario.outbox;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
@EnableConfigurationProperties(OutboxConfig.Propiedades.class)
public class OutboxConfig {
    @Validated
    @ConfigurationProperties(prefix = "inventario.outbox")
    public record Propiedades(@Min(100) @Max(30000) long confirmTimeoutMs,
                              @Min(1) @Max(300) int reintentoSegundos,
                              @Min(100) long intervaloMs) { }
}

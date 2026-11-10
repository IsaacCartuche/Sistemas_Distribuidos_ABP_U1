package com.empresa.inventario.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.validation.annotation.Validated;
import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(InventarioConfig.Propiedades.class)
public class InventarioConfig {
    @Bean
    Clock reloj() { return Clock.systemUTC(); }

    @Validated
    @ConfigurationProperties(prefix = "inventario")
    public record Propiedades(@Min(1) @Max(86400) int ttlSegundos,
                              @Min(100) long intervaloExpiracionMs) { }
}

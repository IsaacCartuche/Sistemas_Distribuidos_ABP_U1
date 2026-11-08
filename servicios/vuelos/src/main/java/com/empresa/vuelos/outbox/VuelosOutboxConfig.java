package com.empresa.vuelos.outbox;

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
@EnableConfigurationProperties(VuelosOutboxConfig.Propiedades.class)
public class VuelosOutboxConfig {
    @Bean Clock relojVuelos() { return Clock.systemUTC(); }

    @Validated
    @ConfigurationProperties(prefix = "vuelos.outbox")
    public record Propiedades(@Min(100) @Max(30000) long confirmTimeoutMs,
                              @Min(1) @Max(300) int reintentoSegundos,
                              @Min(100) long intervaloMs) { }
}

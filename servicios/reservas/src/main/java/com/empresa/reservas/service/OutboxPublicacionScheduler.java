package com.empresa.reservas.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "outbox.publicacion.habilitada",
        havingValue = "true"
)
public class OutboxPublicacionScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(OutboxPublicacionScheduler.class);

    private final OutboxProcesamientoService procesamientoService;

    public OutboxPublicacionScheduler(
            OutboxProcesamientoService procesamientoService) {
        this.procesamientoService = procesamientoService;
    }

    @Scheduled(
        fixedDelayString = "${outbox.publicacion.intervalo-ms:10000}",
        initialDelayString = "${outbox.publicacion.espera-inicial-ms:10000}"
    )
    public void publicarPendientes() {
        try {
            procesamientoService.procesarPendientes();
        } catch (Exception excepcion) {
            log.error(
                    "Error durante el procesamiento programado del Outbox",
                    excepcion
            );
        }
    }
}

package com.empresa.pagos.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="outbox.recuperacion.habilitada", havingValue="true", matchIfMissing=true)
@Component
public class OutboxRecuperacionScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(OutboxRecuperacionScheduler.class);

    private final OutboxRecuperacionService recuperacionService;

    public OutboxRecuperacionScheduler(
            OutboxRecuperacionService recuperacionService) {
        this.recuperacionService = recuperacionService;
    }

    @Scheduled(
        fixedDelayString = "${outbox.recuperacion.intervalo-ms:60000}",
        initialDelayString = "${outbox.recuperacion.espera-inicial-ms:60000}"
    )
    public void recuperarEventos() {
        int recuperados = recuperacionService.recuperarAbandonados();

        log.info("Recuperación Outbox ejecutada. Eventos recuperados: {}",
                recuperados);
    }
}

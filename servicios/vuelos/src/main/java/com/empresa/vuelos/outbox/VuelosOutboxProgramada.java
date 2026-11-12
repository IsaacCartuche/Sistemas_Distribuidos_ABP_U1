package com.empresa.vuelos.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "vuelos.outbox.habilitada", havingValue = "true", matchIfMissing = true)
public class VuelosOutboxProgramada {
    private final VuelosOutboxWorker worker;
    public VuelosOutboxProgramada(VuelosOutboxWorker worker) { this.worker = worker; }
    @Scheduled(fixedDelayString = "${vuelos.outbox.intervalo-ms:5000}")
    public void ejecutar() { worker.publicarUno(); }
}

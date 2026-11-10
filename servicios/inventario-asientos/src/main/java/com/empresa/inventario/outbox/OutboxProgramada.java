package com.empresa.inventario.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "inventario.outbox.habilitada", havingValue = "true", matchIfMissing = true)
public class OutboxProgramada {
    private final OutboxWorker worker;
    public OutboxProgramada(OutboxWorker worker) { this.worker = worker; }
    @Scheduled(fixedDelayString = "${inventario.outbox.intervalo-ms:5000}")
    public void ejecutar() {
        // Un evento por ciclo mantiene el uso de conexiones y la duración del trabajo acotados.
        worker.publicarUno();
    }
}

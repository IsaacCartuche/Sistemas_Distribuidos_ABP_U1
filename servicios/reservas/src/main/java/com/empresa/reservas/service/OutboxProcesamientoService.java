
package com.empresa.reservas.service;

import com.empresa.reservas.entity.OutboxEvento;
import com.empresa.reservas.repository.OutboxEventoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class OutboxProcesamientoService {

    private static final Logger log =
            LoggerFactory.getLogger(OutboxProcesamientoService.class);

    private final OutboxReclamacionService reclamacionService;
    private final OutboxRabbitPublisher publisher;
    private final OutboxEventoRepository repository;

    public OutboxProcesamientoService(
            OutboxReclamacionService reclamacionService,
            OutboxRabbitPublisher publisher,
            OutboxEventoRepository repository) {
        this.reclamacionService = reclamacionService;
        this.publisher = publisher;
        this.repository = repository;
    }

    public void procesarPendientes() {
        UUID instanciaId = UUID.randomUUID();

        List<OutboxEvento> eventos =
                reclamacionService.reclamarPendientes(instanciaId);

        for (OutboxEvento evento : eventos) {
            try {
                boolean publicado;

                try {
                    publicado = publisher.publicar(evento);
                } catch (Exception excepcion) {
                    log.error(
                            "Error publicando evento Outbox {}",
                            evento.getId(),
                            excepcion
                    );
                    publicado = false;
                }

                if (publicado) {
                    int actualizados = repository.marcarPublicado(
                            evento.getId(),
                            instanciaId,
                            LocalDateTime.now()
                    );

                    if (actualizados != 1) {
                        log.warn(
                                "No se pudo marcar como PUBLICADO el evento {}",
                                evento.getId()
                        );
                    }
                } else {
                    int actualizados = repository.devolverAPendiente(
                            evento.getId(),
                            instanciaId
                    );

                    if (actualizados != 1) {
                        log.warn(
                                "No se pudo devolver a PENDIENTE el evento {}",
                                evento.getId()
                        );
                    }
                }

            } catch (Exception excepcion) {
                log.error(
                        "Error procesando evento Outbox {}. "
                        + "Se continuará con el siguiente evento.",
                        evento.getId(),
                        excepcion
                );
            }
        }
    }
}

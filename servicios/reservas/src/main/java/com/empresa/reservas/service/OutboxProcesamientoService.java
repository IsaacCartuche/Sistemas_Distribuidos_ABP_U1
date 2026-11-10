package com.empresa.reservas.service;

import com.empresa.reservas.entity.OutboxEvento;
import com.empresa.reservas.repository.OutboxEventoRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class OutboxProcesamientoService {

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

        List<OutboxEvento> eventos = reclamacionService.reclamarPendientes(instanciaId);

        for (OutboxEvento evento : eventos) {
            boolean publicado;

            try {
                publicado = publisher.publicar(evento);
            } catch (Exception excepcion) {
                publicado = false;
            }

            if (publicado) {
                repository.marcarPublicado(
                        evento.getId(),
                        instanciaId,
                        LocalDateTime.now());
            } else {
                repository.devolverAPendiente(
                        evento.getId(),
                        instanciaId);
            }
        }
    }
}

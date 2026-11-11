package com.empresa.pagos.service;

import com.empresa.pagos.entity.OutboxEvento;
import com.empresa.pagos.repository.OutboxEventoRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class OutboxReclamacionService {

    private final OutboxEventoRepository repository;

    public OutboxReclamacionService(OutboxEventoRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<OutboxEvento> reclamarPendientes(UUID instanciaId) {

        List<OutboxEvento> eventos =
                repository.bloquearEventosPendientes("PENDIENTE");

        LocalDateTime ahora = LocalDateTime.now();

        for (OutboxEvento evento : eventos) {
            evento.setEstado("EN_PROCESO");
            evento.setFechaReclamo(ahora);
            evento.setReclamadoPor(instanciaId);
            evento.setIntentos(evento.getIntentos() + 1);
        }

        repository.saveAll(eventos);
        repository.flush();

        return eventos;
    }
}

package com.empresa.reservas.service;

import com.empresa.reservas.repository.OutboxEventoRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class OutboxRecuperacionService {

    private static final int MINUTOS_EXPIRACION = 5;

    private final OutboxEventoRepository repository;

    public OutboxRecuperacionService(OutboxEventoRepository repository) {
        this.repository = repository;
    }

    public int recuperarAbandonados() {
        LocalDateTime limite = LocalDateTime.now()
                .minusMinutes(MINUTOS_EXPIRACION);

        return repository.recuperarEventosAbandonados(limite);
    }
}

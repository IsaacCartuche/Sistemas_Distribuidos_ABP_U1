
package com.empresa.reservas.service;

import com.empresa.reservas.entity.Reserva;
import com.empresa.reservas.entity.OutboxEvento;
import com.empresa.reservas.event.ReservaEventoFactory;
import com.empresa.reservas.repository.ReservaRepository;
import com.empresa.reservas.repository.OutboxEventoRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservaPersistenceService {

    private final ReservaRepository reservaRepository;
    private final OutboxEventoRepository outboxEventoRepository;
    private final ReservaEventoFactory eventoFactory;

    public ReservaPersistenceService(
            ReservaRepository reservaRepository,
            OutboxEventoRepository outboxEventoRepository,
            ReservaEventoFactory eventoFactory) {

        this.reservaRepository = reservaRepository;
        this.outboxEventoRepository = outboxEventoRepository;
        this.eventoFactory = eventoFactory;
    }

    @Transactional
    public Reserva guardar(Reserva reserva) {
        return reservaRepository.saveAndFlush(reserva);
    }

    @Transactional
    public Reserva guardarConEvento(Reserva reserva) {

        Reserva reservaGuardada =
                reservaRepository.saveAndFlush(reserva);

        OutboxEvento evento =
                eventoFactory.crearEventoReservaCreada(reservaGuardada);

        outboxEventoRepository.saveAndFlush(evento);

        return reservaGuardada;
    }
}

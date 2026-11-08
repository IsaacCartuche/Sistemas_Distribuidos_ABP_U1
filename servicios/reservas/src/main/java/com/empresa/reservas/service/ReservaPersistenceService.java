package com.empresa.reservas.service;

import com.empresa.reservas.entity.Reserva;
import com.empresa.reservas.repository.ReservaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservaPersistenceService {

    private final ReservaRepository reservaRepository;

    public ReservaPersistenceService(ReservaRepository reservaRepository) {
        this.reservaRepository = reservaRepository;
    }

    @Transactional
    public Reserva guardar(Reserva reserva) {
        return reservaRepository.saveAndFlush(reserva);
    }
}

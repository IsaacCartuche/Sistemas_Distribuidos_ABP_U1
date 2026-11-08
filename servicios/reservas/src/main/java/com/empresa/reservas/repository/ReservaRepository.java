package com.empresa.reservas.repository;

import com.empresa.reservas.entity.Reserva;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReservaRepository extends JpaRepository<Reserva, Integer> {

    Optional<Reserva> findByIdempotencyKey(String idempotencyKey);

}

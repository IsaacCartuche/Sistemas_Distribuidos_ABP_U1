package com.empresa.reservas.repository;

import com.empresa.reservas.entity.Reserva;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReservaRepository extends JpaRepository<Reserva, Integer> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Reserva r where r.correlationId = :correlationId")
    Optional<Reserva> bloquearPorCorrelationId(@org.springframework.data.repository.query.Param("correlationId") java.util.UUID correlationId);

    Optional<Reserva> findByIdempotencyKey(String idempotencyKey);

}

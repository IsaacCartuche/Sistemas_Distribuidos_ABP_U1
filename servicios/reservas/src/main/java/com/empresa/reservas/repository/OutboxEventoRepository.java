package com.empresa.reservas.repository;

import com.empresa.reservas.entity.OutboxEvento;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

public interface OutboxEventoRepository extends JpaRepository<OutboxEvento, UUID> {

    List<OutboxEvento> findTop20ByEstadoOrderByFechaCreacionAsc(String estado);
    Optional<OutboxEvento> findByCorrelationId(UUID correlationId);
}

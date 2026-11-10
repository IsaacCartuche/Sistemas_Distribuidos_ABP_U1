package com.empresa.reservas.repository;

import com.empresa.reservas.entity.OutboxEvento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventoRepository
        extends JpaRepository<OutboxEvento, UUID> {

    List<OutboxEvento> findTop20ByEstadoOrderByFechaCreacionAsc(
            String estado
    );

    Optional<OutboxEvento> findByCorrelationId(UUID correlationId);

    @Query(value = """
            SELECT *
            FROM outbox_eventos
            WHERE estado = :estado
            ORDER BY fecha_creacion ASC
            LIMIT 20
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvento> bloquearEventosPendientes(
            @Param("estado") String estado
    );
}

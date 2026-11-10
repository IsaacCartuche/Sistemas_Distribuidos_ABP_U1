package com.empresa.reservas.repository;

import com.empresa.reservas.entity.OutboxEvento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;

public interface OutboxEventoRepository
                extends JpaRepository<OutboxEvento, UUID> {

        List<OutboxEvento> findTop20ByEstadoOrderByFechaCreacionAsc(
                        String estado);

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
                        @Param("estado") String estado);

        @Modifying
        @Transactional
        @Query(value = """
                        UPDATE outbox_eventos
                        SET estado = 'PENDIENTE',
                            fecha_reclamo = NULL,
                            reclamado_por = NULL
                        WHERE estado = 'EN_PROCESO'
                          AND fecha_reclamo < :limite
                        """, nativeQuery = true)
        int recuperarEventosAbandonados(
                        @Param("limite") LocalDateTime limite);

        @Modifying
        @Transactional
        @Query(value = """
                        UPDATE outbox_eventos
                        SET estado = 'PUBLICADO',
                            fecha_publicacion = :fechaPublicacion,
                            fecha_reclamo = NULL,
                            reclamado_por = NULL
                        WHERE id = :eventoId
                          AND estado = 'EN_PROCESO'
                          AND reclamado_por = :instanciaId
                        """, nativeQuery = true)
        int marcarPublicado(
                        @Param("eventoId") UUID eventoId,
                        @Param("instanciaId") UUID instanciaId,
                        @Param("fechaPublicacion") LocalDateTime fechaPublicacion);

        @Modifying
        @Transactional
        @Query(value = """
                        UPDATE outbox_eventos
                        SET estado = 'PENDIENTE',
                            fecha_reclamo = NULL,
                            reclamado_por = NULL
                        WHERE id = :eventoId
                          AND estado = 'EN_PROCESO'
                          AND reclamado_por = :instanciaId
                        """, nativeQuery = true)
        int devolverAPendiente(
                        @Param("eventoId") UUID eventoId,
                        @Param("instanciaId") UUID instanciaId);

}

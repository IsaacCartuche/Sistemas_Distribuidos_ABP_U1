package com.empresa.inventario.persistence;

import com.empresa.inventario.domain.Asiento;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AsientoRepository extends JpaRepository<Asiento, Integer> {
    List<Asiento> findByVueloIdOrderByNumeroAsiento(Integer vueloId);
    Optional<Asiento> findByVueloIdAndNumeroAsiento(Integer vueloId, String numeroAsiento);

    @Query("""
            select a from Asiento a where a.vueloId = :vuelo
            and (a.estado = com.empresa.inventario.domain.EstadoAsiento.DISPONIBLE
            or (a.estado = com.empresa.inventario.domain.EstadoAsiento.BLOQUEADO and a.bloqueadoHasta <= :ahora))
            order by a.numeroAsiento
            """)
    List<Asiento> disponibles(@Param("vuelo") Integer vuelo, @Param("ahora") LocalDateTime ahora);

    @Query("""
            select a.id from Asiento a
            where a.estado = com.empresa.inventario.domain.EstadoAsiento.BLOQUEADO
            and a.bloqueadoHasta <= :ahora order by a.id
            """)
    List<Integer> candidatosExpiracion(@Param("ahora") LocalDateTime ahora, Pageable limite);

    @Modifying
    @Query(value = """
            insert into asientos(vuelo_id, numero_asiento, estado, version)
            values (:vuelo, :numero, 'DISPONIBLE', 0)
            on conflict (vuelo_id, numero_asiento) do nothing
            """, nativeQuery = true)
    int insertarSiAusente(@Param("vuelo") Integer vuelo, @Param("numero") String numero);
}

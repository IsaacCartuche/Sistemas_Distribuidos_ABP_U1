package com.empresa.vuelos.persistence;

import com.empresa.vuelos.domain.Vuelo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface VueloRepository extends JpaRepository<Vuelo, Integer> {

    @Query("""
            select v from Vuelo v
            where (:origen is null or lower(v.origen) = lower(:origen))
              and (:destino is null or lower(v.destino) = lower(:destino))
              and (:fecha is null or v.fechaSalida = :fecha)
            order by v.fechaSalida, v.horaSalida, v.id
            """)
    List<Vuelo> buscar(@Param("origen") String origen, @Param("destino") String destino,
                       @Param("fecha") LocalDate fecha);
}

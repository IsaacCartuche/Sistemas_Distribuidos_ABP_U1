package com.empresa.vuelos.persistence;

import com.empresa.vuelos.domain.Vuelo;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public interface VueloRepository extends JpaRepository<Vuelo, Integer>, JpaSpecificationExecutor<Vuelo> {

    default List<Vuelo> buscar(String origen, String destino, LocalDate fecha) {
        // Solo se añaden filtros presentes: no se envían parámetros nulos sin tipo a PostgreSQL.
        return findAll((root, query, builder) -> {
            List<Predicate> filtros = new ArrayList<>();
            if (origen != null) {
                filtros.add(builder.equal(builder.lower(root.get("origen")), origen.toLowerCase(Locale.ROOT)));
            }
            if (destino != null) {
                filtros.add(builder.equal(builder.lower(root.get("destino")), destino.toLowerCase(Locale.ROOT)));
            }
            if (fecha != null) {
                filtros.add(builder.equal(root.get("fechaSalida"), fecha));
            }
            return builder.and(filtros.toArray(Predicate[]::new));
        }, Sort.by("fechaSalida", "horaSalida", "id"));
    }
}

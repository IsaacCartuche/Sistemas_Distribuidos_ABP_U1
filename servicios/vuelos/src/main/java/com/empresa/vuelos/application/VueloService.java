package com.empresa.vuelos.application;

import com.empresa.vuelos.api.dto.VueloRequest;
import com.empresa.vuelos.api.dto.VueloResponse;
import com.empresa.vuelos.domain.Vuelo;
import com.empresa.vuelos.persistence.VueloRepository;
import com.empresa.vuelos.outbox.VueloOutboxRegistro;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class VueloService {

    private final VueloRepository repository;
    private final VueloOutboxRegistro outbox;

    public VueloService(VueloRepository repository, VueloOutboxRegistro outbox) {
        this.repository = repository;
        this.outbox = outbox;
    }

    @Transactional
    public VueloResponse crear(VueloRequest request) {
        validarReglas(request);
        Vuelo vuelo = new Vuelo(request.origen().strip(), request.destino().strip(), request.fechaSalida(),
                request.horaSalida(), request.precio(), request.estado(), request.capacidadTotal(),
                request.capacidadDisponible());
        return VueloResponse.from(repository.save(vuelo));
    }

    public VueloResponse consultar(Integer id) {
        return VueloResponse.from(obtener(id));
    }

    public List<VueloResponse> listar(String origen, String destino, LocalDate fecha) {
        return repository.buscar(normalizarFiltro(origen), normalizarFiltro(destino), fecha)
                .stream().map(VueloResponse::from).toList();
    }

    @Transactional
    public VueloResponse actualizar(Integer id, VueloRequest request) {
        validarReglas(request);
        Vuelo vuelo = obtener(id);
        if (coincide(vuelo, request)) return VueloResponse.from(vuelo);
        vuelo.actualizar(request.origen().strip(), request.destino().strip(), request.fechaSalida(),
                request.horaSalida(), request.precio(), request.estado(), request.capacidadTotal(),
                request.capacidadDisponible());
        repository.save(vuelo);
        repository.flush(); // Materializa el UPDATE optimista y su versión antes de serializar el evento.
        outbox.registrar(vuelo);
        return VueloResponse.from(vuelo);
    }

    private boolean coincide(Vuelo vuelo, VueloRequest request) {
        return Objects.equals(vuelo.getOrigen(), request.origen().strip())
                && Objects.equals(vuelo.getDestino(), request.destino().strip())
                && Objects.equals(vuelo.getFechaSalida(), request.fechaSalida())
                && Objects.equals(vuelo.getHoraSalida(), horaPersistible(request.horaSalida()))
                && vuelo.getPrecio().compareTo(request.precio()) == 0
                && vuelo.getEstado() == request.estado()
                && Objects.equals(vuelo.getCapacidadTotal(), request.capacidadTotal())
                && Objects.equals(vuelo.getCapacidadDisponible(), request.capacidadDisponible());
    }

    private LocalTime horaPersistible(LocalTime hora) {
        // Hibernate 6.3 convierte LocalTime a java.sql.Time redondeando a milisegundos.
        // Comparar esa representación evita eventos repetidos por fracciones que el mapper no conserva.
        return hora.plusNanos(500_000).truncatedTo(ChronoUnit.MILLIS);
    }

    private Vuelo obtener(Integer id) {
        return repository.findById(id).orElseThrow(() -> new VueloNoEncontradoException(id));
    }

    private void validarReglas(VueloRequest request) {
        if (request.origen().strip().equalsIgnoreCase(request.destino().strip())) {
            throw new ReglaNegocioException("El origen y el destino deben ser distintos");
        }
        if (request.capacidadDisponible() > request.capacidadTotal()) {
            throw new ReglaNegocioException("La capacidad disponible no puede superar la capacidad total");
        }
    }

    private String normalizarFiltro(String filtro) {
        if (filtro == null) {
            return null;
        }
        String valor = filtro.strip();
        if (valor.isEmpty() || valor.length() > 100) {
            throw new ReglaNegocioException("Los filtros de origen y destino deben tener entre 1 y 100 caracteres");
        }
        return valor;
    }
}

package com.empresa.vuelos.application;

import com.empresa.vuelos.api.dto.VueloRequest;
import com.empresa.vuelos.api.dto.VueloResponse;
import com.empresa.vuelos.domain.Vuelo;
import com.empresa.vuelos.persistence.VueloRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class VueloService {

    private final VueloRepository repository;

    public VueloService(VueloRepository repository) {
        this.repository = repository;
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
        vuelo.actualizar(request.origen().strip(), request.destino().strip(), request.fechaSalida(),
                request.horaSalida(), request.precio(), request.estado(), request.capacidadTotal(),
                request.capacidadDisponible());
        return VueloResponse.from(repository.save(vuelo));
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

package com.empresa.vuelos.api;

import com.empresa.vuelos.api.dto.VueloRequest;
import com.empresa.vuelos.api.dto.VueloResponse;
import com.empresa.vuelos.application.VueloService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping({"/api/v1/vuelos", "/api/vuelos"})
@Validated
public class VueloController {

    private final VueloService service;

    public VueloController(VueloService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<VueloResponse> crear(@Valid @RequestBody VueloRequest request) {
        VueloResponse response = service.crear(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public VueloResponse consultar(@PathVariable @Positive Integer id) {
        return service.consultar(id);
    }

    @GetMapping
    public List<VueloResponse> listar(@RequestParam(required = false) String origen,
                                     @RequestParam(required = false) String destino,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        return service.listar(origen, destino, fecha);
    }

    @PutMapping("/{id}")
    public VueloResponse actualizar(@PathVariable @Positive Integer id,
                                    @Valid @RequestBody VueloRequest request) {
        return service.actualizar(id, request);
    }
}

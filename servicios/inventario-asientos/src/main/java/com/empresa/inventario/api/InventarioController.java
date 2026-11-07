package com.empresa.inventario.api;

import com.empresa.inventario.api.InventarioDto.*;
import com.empresa.inventario.application.InventarioService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/inventario")
public class InventarioController {
    private final InventarioService service;
    public InventarioController(InventarioService service) { this.service = service; }

    @GetMapping("/vuelos/{vueloId}/asientos")
    public List<AsientoResponse> listar(@PathVariable @Positive Integer vueloId,
                                      @RequestParam(defaultValue = "false") boolean disponibles) {
        return service.listar(vueloId, disponibles);
    }
    @PutMapping("/vuelos/{vueloId}/asientos")
    public List<AsientoResponse> inicializar(@PathVariable @Positive Integer vueloId,
                                           @Valid @RequestBody InicializarRequest request) {
        return service.inicializar(vueloId, request);
    }
    @GetMapping("/asientos/{id}")
    public AsientoResponse consultar(@PathVariable @Positive Integer id) { return service.consultar(id); }

    @PutMapping("/asientos/{id}/bloqueos/{token}")
    public RetencionResponse bloquear(@PathVariable @Positive Integer id, @PathVariable UUID token) {
        return service.bloquear(id, token);
    }
    @PostMapping("/asientos/{id}/bloqueos/{token}/confirmacion")
    public RetencionResponse confirmar(@PathVariable @Positive Integer id, @PathVariable UUID token) {
        return service.confirmar(id, token);
    }
    @DeleteMapping("/asientos/{id}/bloqueos/{token}")
    public RetencionResponse liberar(@PathVariable @Positive Integer id, @PathVariable UUID token) {
        return service.liberar(id, token);
    }
}

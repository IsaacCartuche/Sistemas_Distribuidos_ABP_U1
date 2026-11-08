package com.empresa.reservas.controller;

import com.empresa.reservas.service.ReservaService;
import com.empresa.reservas.dto.ReservaResponse;
import com.empresa.reservas.dto.CrearReservaRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/reservas")
public class ReservaController {

    private final ReservaService reservaService;

    public ReservaController(ReservaService reservaService) {
        this.reservaService = reservaService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReservaResponse> obtenerReservaPorId(
            @PathVariable Integer id) {

        return reservaService.buscarPorId(id)
                .map(ReservaResponse::desdeEntidad)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ReservaResponse> crearReserva(
            @RequestBody CrearReservaRequest request) {

        var existente = reservaService.buscarPorIdempotencyKey(
                request.idempotencyKey());

        var reserva = reservaService.crearReserva(request);

        var respuesta = ReservaResponse.desdeEntidad(reserva);

        if (existente.isPresent()) {
            return ResponseEntity.ok(respuesta);
        }

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(respuesta);
    }

}

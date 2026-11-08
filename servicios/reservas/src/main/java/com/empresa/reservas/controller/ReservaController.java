package com.empresa.reservas.controller;

import com.empresa.reservas.service.ReservaService;
import com.empresa.reservas.dto.ReservaResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

}

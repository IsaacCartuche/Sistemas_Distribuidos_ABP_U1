package com.empresa.pagos.controller;

import com.empresa.pagos.dto.PagoResponse;
import com.empresa.pagos.dto.ProcesarPagoRequest;
import com.empresa.pagos.service.PagoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pagos")
@RequiredArgsConstructor
public class PagoController {

    private final PagoService pagoService;

    @PostMapping
    public ResponseEntity<PagoResponse> procesarPago(@Valid @RequestBody ProcesarPagoRequest request) {
        PagoResponse response = pagoService.procesarPago(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/reserva/{reservaId}")
    public ResponseEntity<PagoResponse> obtenerPagoPorReserva(@PathVariable Long reservaId) {
        return pagoService.buscarPorReservaId(reservaId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
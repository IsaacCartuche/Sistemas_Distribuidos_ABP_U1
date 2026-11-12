package com.empresa.pagos.service;

import com.empresa.pagos.dto.PagoResponse;
import com.empresa.pagos.dto.ProcesarPagoRequest;
import com.empresa.pagos.model.Pago;
import com.empresa.pagos.repository.PagoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PagoService {

    private final PagoRepository pagoRepository;

    @Transactional
    public PagoResponse procesarPago(ProcesarPagoRequest request) {
        // Verificar idempotencia
        if (request.getIdempotencyKey() != null) {
            Optional<Pago> existente = pagoRepository.findByIdempotencyKey(request.getIdempotencyKey());
            if (existente.isPresent()) {
                return PagoResponse.desdeEntidad(existente.get());
            }
        }

        // Verificar si ya existe un pago para esta reserva
        Optional<Pago> pagoExistente = pagoRepository.findByReservaId(request.getReservaId());
        if (pagoExistente.isPresent()) {
            return PagoResponse.desdeEntidad(pagoExistente.get());
        }

        // Crear nuevo pago
        Pago pago = Pago.builder()
                .reservaId(request.getReservaId())
                .monto(request.getMonto())
                .metodoPago(request.getMetodoPago())
                .estado("COMPLETADO")
                .idempotencyKey(request.getIdempotencyKey())
                .transaccionExterna("TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .build();

        Pago pagoGuardado = pagoRepository.save(pago);
        return PagoResponse.desdeEntidad(pagoGuardado);
    }

    public Optional<PagoResponse> buscarPorReservaId(Long reservaId) {
        return pagoRepository.findByReservaId(reservaId)
                .map(PagoResponse::desdeEntidad);
    }

    public Optional<PagoResponse> buscarPorIdempotencyKey(String idempotencyKey) {
        return pagoRepository.findByIdempotencyKey(idempotencyKey)
                .map(PagoResponse::desdeEntidad);
    }
}
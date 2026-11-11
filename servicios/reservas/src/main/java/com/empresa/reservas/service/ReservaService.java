package com.empresa.reservas.service;

import com.empresa.reservas.entity.Reserva;
import com.empresa.reservas.repository.ReservaRepository;
import com.empresa.reservas.dto.CrearReservaRequest;
import org.springframework.stereotype.Service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import java.time.LocalDateTime;
import java.math.BigDecimal;

@Service
public class ReservaService {

    private final ReservaRepository reservaRepository;
    private final ReservaPersistenceService persistenceService;
    private final TransactionTemplate transactionTemplate;

    public ReservaService(
            ReservaRepository reservaRepository,
            ReservaPersistenceService persistenceService,
            TransactionTemplate transactionTemplate) {

        this.reservaRepository = reservaRepository;
        this.persistenceService = persistenceService;
        this.transactionTemplate = transactionTemplate;
    }

    public Optional<Reserva> buscarPorId(Integer id) {
        return reservaRepository.findById(id);
    }

    public Optional<Reserva> buscarPorIdempotencyKey(String clave) {
        return reservaRepository.findByIdempotencyKey(clave);
    }

    public Reserva crearReserva(CrearReservaRequest request) {

        if (request == null
                || request.vueloId() == null
                || request.vueloId() <= 0
                || request.usuarioId() == null
                || request.usuarioId() <= 0
                || request.asiento() == null
                || !request.asiento().matches("[A-Za-z0-9-]{1,10}")
                || request.total() == null
                || request.total().compareTo(BigDecimal.ZERO) <= 0
                || request.total().scale() > 2
                || request.total().compareTo(new BigDecimal("99999999.99")) > 0
                || request.idempotencyKey() == null
                || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 100) {
            throw new IllegalArgumentException(
                    "Datos de reserva invalidos");
        }

        String clave = request.idempotencyKey();

        Optional<Reserva> existente = reservaRepository.findByIdempotencyKey(clave);

        if (existente.isPresent()) {
            Reserva anterior = existente.get();

            if (!anterior.getVueloId().equals(request.vueloId())
                    || !anterior.getUsuarioId().equals(request.usuarioId())
                    || !anterior.getAsiento().equals(request.asiento())
                    || anterior.getTotal().compareTo(request.total()) != 0) {
                throw new IllegalArgumentException(
                        "Clave de idempotencia reutilizada con datos diferentes");
            }

            return anterior;
        }

        Reserva reserva = new Reserva();

        reserva.setVueloId(request.vueloId());
        reserva.setUsuarioId(request.usuarioId());
        reserva.setAsiento(request.asiento());
        reserva.setTotal(request.total());
        reserva.setIdempotencyKey(clave);

        reserva.setEstado("PENDIENTE");
        reserva.setFechaCreacion(LocalDateTime.now());

        reserva.setCorrelationId(UUID.randomUUID());
        reserva.setToken(UUID.randomUUID());

        try {
            return persistenceService.guardarConEvento(reserva);

        } catch (DataIntegrityViolationException ex) {

            Optional<Reserva> encontrada = transactionTemplate.execute(
                    status -> reservaRepository.findByIdempotencyKey(clave));

            if (encontrada == null || encontrada.isEmpty()) {
                throw ex;
            }

            Reserva anterior = encontrada.get();

            if (!anterior.getVueloId().equals(request.vueloId())
                    || !anterior.getUsuarioId().equals(request.usuarioId())
                    || !anterior.getAsiento().equals(request.asiento())
                    || anterior.getTotal().compareTo(request.total()) != 0) {

                throw new IllegalArgumentException(
                        "Clave de idempotencia reutilizada con datos diferentes");
            }

            return anterior;
        }
    }

}

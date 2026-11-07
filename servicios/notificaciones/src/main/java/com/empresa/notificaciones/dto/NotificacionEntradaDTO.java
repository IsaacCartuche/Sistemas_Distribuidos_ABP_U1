package com.empresa.notificaciones.dto;

/**
 * DTO de entrada para eventos de negocio recibidos por RabbitMQ.
 * Otros servicios publican eventos aquí; este servicio los consume sin llamadas directas.
 */
public record NotificacionEntradaDTO(
    String usuarioId,
    String tipo,
    String mensaje
) {}

package com.empresa.websocket.dto;

import java.time.LocalDateTime;

/**
 * Objeto de transferencia para notificaciones enviadas por WebSocket.
 * Se publica en RabbitMQ (cola notificaciones.baja) y se reenvía al cliente vía WebSocket.
 */
public record NotificacionDTO(
    String usuarioId,
    String tipo,
    String mensaje,
    LocalDateTime fecha
) {}

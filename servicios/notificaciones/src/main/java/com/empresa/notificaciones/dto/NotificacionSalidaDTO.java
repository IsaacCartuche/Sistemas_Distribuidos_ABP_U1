package com.empresa.notificaciones.dto;

import java.time.LocalDateTime;

/**
 * DTO de salida para reenviar notificaciones a RabbitMQ (cola notificaciones.baja).
 * Cada microservicio define sus propios DTOs; este servicio no depende de clases
 * de websocket-server para mantener la independencia de despliegue.
 */
public record NotificacionSalidaDTO(
    String usuarioId,
    String tipo,
    String mensaje,
    LocalDateTime fecha
) {}

package com.empresa.notificaciones.dto;

import java.time.LocalDateTime;

/**
 * DTO de salida para la API REST del frontend.
 */
public record NotificacionRespuestaDTO(
    Long id,
    Integer usuarioId,
    String tipo,
    String mensaje,
    LocalDateTime fechaEnvio,
    boolean leida
) {}

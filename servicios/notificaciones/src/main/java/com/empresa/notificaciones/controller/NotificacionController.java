package com.empresa.notificaciones.controller;

import com.empresa.notificaciones.dto.NotificacionRespuestaDTO;
import com.empresa.notificaciones.service.NotificacionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * API REST para el frontend. Solo expone consultas de notificaciones del usuario autenticado.
 * No expone escritura directa; las notificaciones se crean vía eventos de RabbitMQ.
 */
@RestController
@RequestMapping("/notificaciones")
public class NotificacionController {

    private final NotificacionService notificacionService;

    public NotificacionController(NotificacionService notificacionService) {
        this.notificacionService = notificacionService;
    }

    @GetMapping("/usuario/{usuarioId}")
    public ResponseEntity<List<NotificacionRespuestaDTO>> listar(@PathVariable Integer usuarioId) {
        List<NotificacionRespuestaDTO> notificaciones = notificacionService.listarPorUsuario(usuarioId);
        return ResponseEntity.ok(notificaciones);
    }

    @PatchMapping("/{id}/leida")
    public ResponseEntity<Void> marcarLeida(@PathVariable Long id,
                                            @RequestParam Integer usuarioId) {
        notificacionService.marcarLeida(id, usuarioId);
        return ResponseEntity.noContent().build();
    }
}

package com.empresa.websocket.service;

import com.empresa.websocket.dto.NotificacionDTO;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Servicio responsable de entregar notificaciones a un usuario específico vía WebSocket.
 * Usa convertAndSendToUser para que el mensaje llegue solo a la sesión del usuario indicado.
 */
@Service
public class NotificacionService {

    private final SimpMessagingTemplate messagingTemplate;

    public NotificacionService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void enviarANotificacion(String usuarioId, NotificacionDTO notificacion) {
        messagingTemplate.convertAndSendToUser(usuarioId, "/topic/notificaciones", notificacion);
    }
}

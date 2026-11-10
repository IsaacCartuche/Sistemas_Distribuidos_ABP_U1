package com.empresa.usuarios.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventoUsuarioRegistrado {
    private String eventId;
    private String correlationId;
    private String timestamp;
    private Payload payload;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Payload {
        private Long id;
        private String email;
        private String nombre;
        private String apellido;
        private String rol;
    }
}
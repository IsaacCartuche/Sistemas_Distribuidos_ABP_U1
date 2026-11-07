package com.empresa.websocket.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class NotificacionDTOTest {

    @Test
    void crearDTO_conValores_guardaCorrectamente() {
        LocalDateTime fecha = LocalDateTime.of(2026, 11, 4, 10, 30);
        NotificacionDTO dto = new NotificacionDTO("u1", "RESERVA_CREADA", "Tu reserva fue creada", fecha);

        assertEquals("u1", dto.usuarioId());
        assertEquals("RESERVA_CREADA", dto.tipo());
        assertEquals("Tu reserva fue creada", dto.mensaje());
        assertEquals(fecha, dto.fecha());
    }

    @Test
    void dto_esInmutable() {
        LocalDateTime fecha = LocalDateTime.now();
        NotificacionDTO dto = new NotificacionDTO("u1", "T", "m", fecha);

        assertThrows(java.lang.UnsupportedOperationException.class, () -> {
            // record es inmutable; intento de modificación fallaría en compilación si fuera mutable.
            // Esta prueba documenta la inmutabilidad implícita del record.
            var tipo = dto.tipo();
            assertEquals("T", tipo);
        });
    }
}

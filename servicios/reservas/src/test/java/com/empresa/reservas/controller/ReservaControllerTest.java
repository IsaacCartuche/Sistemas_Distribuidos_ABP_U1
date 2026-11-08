package com.empresa.reservas.controller;

import com.empresa.reservas.entity.Reserva;
import com.empresa.reservas.service.ReservaService;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import com.empresa.reservas.exception.ReservaExceptionHandler;
import com.empresa.reservas.dto.CrearReservaRequest;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReservaControllerTest {

        private final ReservaService reservaService = mock(ReservaService.class);

        private final MockMvc mockMvc = MockMvcBuilders
                        .standaloneSetup(new ReservaController(reservaService))
                        .setControllerAdvice(new ReservaExceptionHandler())
                        .build();

        @Test
        void obtenerReservaExistenteDebeRetornar200() throws Exception {

                Reserva reserva = new Reserva();
                reserva.setVueloId(15);
                reserva.setUsuarioId(7);
                reserva.setAsiento("12A");
                reserva.setEstado("PENDIENTE");
                reserva.setTotal(new BigDecimal("150.00"));
                reserva.setIdempotencyKey("clave-interna");
                reserva.setToken(UUID.randomUUID());
                reserva.setCorrelationId(UUID.randomUUID());

                when(reservaService.buscarPorId(9))
                                .thenReturn(Optional.of(reserva));

                mockMvc.perform(get("/api/reservas/9"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.vueloId").value(15))
                                .andExpect(jsonPath("$.asiento").value("12A"))
                                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                                .andExpect(jsonPath("$.token").doesNotExist())
                                .andExpect(jsonPath("$.correlationId").doesNotExist())
                                .andExpect(jsonPath("$.idempotencyKey").doesNotExist());
        }

        @Test
        void obtenerReservaInexistenteDebeRetornar404() throws Exception {

                when(reservaService.buscarPorId(999999))
                                .thenReturn(Optional.empty());

                mockMvc.perform(get("/api/reservas/999999"))
                                .andExpect(status().isNotFound());
        }

        @Test
        void claveIdempotenciaReutilizadaDebeRetornar409() throws Exception {

                when(reservaService.crearReserva(any(CrearReservaRequest.class)))
                                .thenThrow(new IllegalArgumentException(
                                                "Clave de idempotencia reutilizada con datos diferentes"));

                String json = """
                                {
                                  "vueloId": 15,
                                  "usuarioId": 7,
                                  "asiento": "15A",
                                  "total": 150.00,
                                  "idempotencyKey": "prueba-post-reserva-001"
                                }
                                """;

                mockMvc.perform(post("/api/reservas")
                                .contentType("application/json")
                                .content(json))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.error")
                                                .value("CONFLICTO_IDEMPOTENCIA"))
                                .andExpect(jsonPath("$.mensaje")
                                                .value("Clave de idempotencia reutilizada con datos diferentes"));
        }

        @Test
        void datosInvalidosDebenRetornar400() throws Exception {

                when(reservaService.crearReserva(any(CrearReservaRequest.class)))
                                .thenThrow(new IllegalArgumentException(
                                                "Datos de reserva invalidos"));

                String json = """
                                {
                                  "vueloId": 15,
                                  "usuarioId": 7,
                                  "asiento": "12A",
                                  "total": -50.00,
                                  "idempotencyKey": "reserva-invalida-001"
                                }
                                """;

                mockMvc.perform(post("/api/reservas")
                                .contentType("application/json")
                                .content(json))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.error")
                                                .value("DATOS_INVALIDOS"))
                                .andExpect(jsonPath("$.mensaje")
                                                .value("Datos de reserva invalidos"));
        }

}

package com.empresa.inventario.api;

import com.empresa.inventario.application.*;
import com.empresa.inventario.api.InventarioDto.*;
import com.empresa.inventario.domain.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventarioController.class)
class InventarioControllerTest {
    private static final String BASE = "/api/v1/inventario";
    @Autowired MockMvc mvc;
    @MockBean InventarioService service;

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/inventario", "/api/inventario"})
    void listarDisponiblesDelegaFiltro(String base) throws Exception {
        when(service.listar(1, true)).thenReturn(List.of());
        mvc.perform(get(base + "/vuelos/1/asientos?disponibles=true"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(service).listar(1, true);
    }
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/inventario", "/api/inventario"})
    void inicializarDevuelve200(String base) throws Exception {
        when(service.inicializar(any(), any())).thenReturn(List.of());
        mvc.perform(put(base + "/vuelos/1/asientos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"numeros\":[\"1A\",\"1B\"]}"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(service).inicializar(1, new InicializarRequest(List.of("1A", "1B")));
    }
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"numeros\":[]}", "{\"numeros\":[null]}",
            "{\"numeros\":[\"\"]}", "{\"numeros\":[\"ABCDEFGHIJK\"]}", "{\"numeros\":[\"1 A\"]}", "{"})
    void rechazaInicializacionInvalida(String json) throws Exception {
        mvc.perform(put(BASE + "/vuelos/1/asientos").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "2147483648"})
    void idInvalidoEs400(String id) throws Exception {
        mvc.perform(get(BASE + "/asientos/" + id)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/inventario", "/api/inventario"})
    void tokenInvalidoEs400(String base) throws Exception {
        mvc.perform(put(base + "/asientos/1/bloqueos/invalido")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/inventario", "/api/inventario"})
    void inexistenteEs404(String base) throws Exception {
        when(service.consultar(1)).thenThrow(new InventarioException(404, "Asiento no encontrado"));
        mvc.perform(get(base + "/asientos/1")).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/inventario", "/api/inventario"})
    void conflictoOptimistaEs409SinExponerSql(String base) throws Exception {
        when(service.bloquear(any(), any())).thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException("Asiento", 1));
        mvc.perform(put(base + "/asientos/1/bloqueos/" + UUID.randomUUID()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
    }
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/inventario", "/api/inventario"})
    void confirmarYLiberaDeleganToken(String base) throws Exception {
        UUID token = UUID.randomUUID();
        when(service.confirmar(1, token)).thenReturn(new RetencionResponse(token, 1,
                EstadoRetencion.CONFIRMADA, LocalDateTime.of(2027, 1, 1, 12, 5)));
        when(service.liberar(1, token)).thenReturn(new RetencionResponse(token, 1,
                EstadoRetencion.LIBERADA, LocalDateTime.of(2027, 1, 1, 12, 5)));
        mvc.perform(post(base + "/asientos/1/bloqueos/" + token + "/confirmacion"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CONFIRMADA"));
        mvc.perform(delete(base + "/asientos/1/bloqueos/" + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("LIBERADA"));
        verify(service).confirmar(1, token);
        verify(service).liberar(1, token);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/inventario", "/api/inventario"})
    void listarSinFiltroDelegaDisponiblesFalse(String base) throws Exception {
        when(service.listar(1, false)).thenReturn(List.of());
        mvc.perform(get(base + "/vuelos/1/asientos"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(service).listar(1, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/inventario", "/api/inventario"})
    void consultarDevuelveAsiento(String base) throws Exception {
        when(service.consultar(1)).thenReturn(new AsientoResponse(1, 2, "1A",
                EstadoAsiento.DISPONIBLE, true, null, 0));
        mvc.perform(get(base + "/asientos/1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.numeroAsiento").value("1A"))
                .andExpect(jsonPath("$.disponible").value(true));
        verify(service).consultar(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/inventario", "/api/inventario"})
    void bloquearDevuelveRetencionYDelegaToken(String base) throws Exception {
        UUID token = UUID.randomUUID();
        when(service.bloquear(1, token)).thenReturn(new RetencionResponse(token, 1,
                EstadoRetencion.ACTIVA, LocalDateTime.of(2027, 1, 1, 12, 5)));
        mvc.perform(put(base + "/asientos/1/bloqueos/" + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").value(token.toString()))
                .andExpect(jsonPath("$.estado").value("ACTIVA"));
        verify(service).bloquear(1, token);
    }
}

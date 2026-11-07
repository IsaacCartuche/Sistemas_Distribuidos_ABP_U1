package com.empresa.inventario.api;

import com.empresa.inventario.application.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventarioController.class)
class InventarioControllerTest {
    private static final String BASE = "/api/v1/inventario";
    @Autowired MockMvc mvc;
    @MockBean InventarioService service;

    @Test
    void listarDisponiblesDelegaFiltro() throws Exception {
        when(service.listar(1, true)).thenReturn(List.of());
        mvc.perform(get(BASE + "/vuelos/1/asientos?disponibles=true"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(service).listar(1, true);
    }
    @Test
    void inicializarDevuelve200() throws Exception {
        when(service.inicializar(any(), any())).thenReturn(List.of());
        mvc.perform(put(BASE + "/vuelos/1/asientos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"numeros\":[\"1A\",\"1B\"]}"))
                .andExpect(status().isOk());
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
    @Test
    void tokenInvalidoEs400() throws Exception {
        mvc.perform(put(BASE + "/asientos/1/bloqueos/invalido")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test
    void inexistenteEs404() throws Exception {
        when(service.consultar(1)).thenThrow(new InventarioException(404, "Asiento no encontrado"));
        mvc.perform(get(BASE + "/asientos/1")).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
    @Test
    void conflictoOptimistaEs409SinExponerSql() throws Exception {
        when(service.bloquear(any(), any())).thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException("Asiento", 1));
        mvc.perform(put(BASE + "/asientos/1/bloqueos/" + UUID.randomUUID()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
    }
    @Test
    void confirmarYLiberaDeleganToken() throws Exception {
        UUID token = UUID.randomUUID();
        mvc.perform(post(BASE + "/asientos/1/bloqueos/" + token + "/confirmacion")).andExpect(status().isOk());
        mvc.perform(delete(BASE + "/asientos/1/bloqueos/" + token)).andExpect(status().isOk());
        verify(service).confirmar(1, token);
        verify(service).liberar(1, token);
    }
}

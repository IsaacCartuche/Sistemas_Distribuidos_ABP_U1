package com.empresa.vuelos.api;

import com.empresa.vuelos.api.dto.VueloRequest;
import com.empresa.vuelos.api.dto.VueloResponse;
import com.empresa.vuelos.application.ReglaNegocioException;
import com.empresa.vuelos.application.VueloNoEncontradoException;
import com.empresa.vuelos.application.VueloService;
import com.empresa.vuelos.domain.EstadoVuelo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VueloController.class)
class VueloControllerTest {

    private static final String URL = "/api/v1/vuelos";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @MockBean
    private VueloService service;

    @Test
    void crearDevuelve201LocationYRepresentacion() throws Exception {
        when(service.crear(any())).thenReturn(response());

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(jsonValido()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost" + URL + "/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.precio").value(125.50))
                .andExpect(jsonPath("$.fechaSalida").value("2027-05-10"))
                .andExpect(jsonPath("$.capacidadDisponible").value(80));
        verify(service).crear(any(VueloRequest.class));
    }

    @Test
    void consultarDevuelve200() throws Exception {
        when(service.consultar(1)).thenReturn(response());

        mvc.perform(get(URL + "/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origen").value("Quito"));
    }

    @Test
    void listarSinFiltrosDevuelveArray() throws Exception {
        when(service.listar(null, null, null)).thenReturn(List.of(response()));

        mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void buscarConvierteFechaYDelegaFiltros() throws Exception {
        when(service.listar("Quito", "Loja", LocalDate.of(2027, 5, 10))).thenReturn(List.of());

        mvc.perform(get(URL).param("origen", "Quito").param("destino", "Loja").param("fecha", "2027-05-10"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        verify(service).listar("Quito", "Loja", LocalDate.of(2027, 5, 10));
    }

    @Test
    void actualizarDevuelve200() throws Exception {
        when(service.actualizar(eq(1), any())).thenReturn(response());

        mvc.perform(put(URL + "/1").contentType(MediaType.APPLICATION_JSON).content(jsonValido()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
        verify(service).actualizar(eq(1), any(VueloRequest.class));
    }

    @Test
    void consultarInexistenteDevuelve404() throws Exception {
        when(service.consultar(99)).thenThrow(new VueloNoEncontradoException(99));

        mvc.perform(get(URL + "/99"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("No existe el vuelo con ID 99"));
    }

    @Test
    void actualizarInexistenteDevuelve404() throws Exception {
        when(service.actualizar(eq(99), any())).thenThrow(new VueloNoEncontradoException(99));

        mvc.perform(put(URL + "/99").contentType(MediaType.APPLICATION_JSON).content(jsonValido()))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"origen", "destino", "fechaSalida", "horaSalida", "precio", "estado",
            "capacidadTotal", "capacidadDisponible"})
    void crearRechazaCamposObligatoriosAusentes(String campo) throws Exception {
        ObjectNode json = (ObjectNode) mapper.readTree(jsonValido());
        json.remove(campo);

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores." + campo).exists());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({"origen,' '", "destino,' '", "precio,0", "precio,-1", "precio,1.234",
            "precio,100000000", "capacidadTotal,0", "capacidadTotal,-1", "capacidadDisponible,-1"})
    void crearRechazaValoresFueraDeLimites(String campo, String valor) throws Exception {
        ObjectNode json = (ObjectNode) mapper.readTree(jsonValido());
        if (campo.equals("origen") || campo.equals("destino")) {
            json.put(campo, valor);
        } else {
            json.put(campo, new BigDecimal(valor));
        }

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores." + campo).exists());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"origen", "destino"})
    void crearRechazaCiudadesDemasiadoLargas(String campo) throws Exception {
        ObjectNode json = (ObjectNode) mapper.readTree(jsonValido());
        json.put(campo, "A".repeat(101));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores." + campo).exists());
        verifyNoInteractions(service);
    }

    @Test
    void actualizarTambienValidaElCuerpo() throws Exception {
        mvc.perform(put(URL + "/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.precio").exists());
        verifyNoInteractions(service);
    }

    @Test
    void reglaDeNegocioDevuelve400() throws Exception {
        when(service.crear(any())).thenThrow(new ReglaNegocioException("El origen y el destino deben ser distintos"));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(jsonValido()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El origen y el destino deben ser distintos"));
    }

    @Test
    void conflictoDePersistenciaNoExponeSql() throws Exception {
        when(service.crear(any())).thenThrow(new DataIntegrityViolationException("detalle SQL privado"));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(jsonValido()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "Los datos del vuelo entran en conflicto con las restricciones de persistencia"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "2147483648"})
    void rechazaIdInvalido(String id) throws Exception {
        mvc.perform(get(URL + "/" + id)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void rechazaFechaDeBusquedaInvalida() throws Exception {
        mvc.perform(get(URL).param("fecha", "2027-02-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Formato inválido del parámetro fecha"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "{\"estado\":\"DESCONOCIDO\"}",
            "{\"fechaSalida\":\"no-es-fecha\"}", "{\"horaSalida\":\"25:00:00\"}"})
    void rechazaJsonYTiposInvalidos(String json) throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(service);
    }

    private String jsonValido() {
        return """
                {
                  "origen": "Quito",
                  "destino": "Loja",
                  "fechaSalida": "2027-05-10",
                  "horaSalida": "09:30:00",
                  "precio": 125.50,
                  "estado": "ACTIVO",
                  "capacidadTotal": 100,
                  "capacidadDisponible": 80
                }
                """;
    }

    private VueloResponse response() {
        return new VueloResponse(1, "Quito", "Loja", LocalDate.of(2027, 5, 10), LocalTime.of(9, 30),
                new BigDecimal("125.50"), EstadoVuelo.ACTIVO, 100, 80, LocalDateTime.of(2026, 1, 1, 10, 0));
    }
}

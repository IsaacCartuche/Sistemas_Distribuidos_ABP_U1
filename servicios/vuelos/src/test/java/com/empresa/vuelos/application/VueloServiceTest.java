package com.empresa.vuelos.application;

import com.empresa.vuelos.api.dto.VueloRequest;
import com.empresa.vuelos.api.dto.VueloResponse;
import com.empresa.vuelos.domain.EstadoVuelo;
import com.empresa.vuelos.domain.Vuelo;
import com.empresa.vuelos.persistence.VueloRepository;
import com.empresa.vuelos.outbox.VueloOutboxRegistro;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class VueloServiceTest {

    @Mock
    private VueloRepository repository;

    private VueloService service;
    private final VueloOutboxRegistro outbox = org.mockito.Mockito.mock(VueloOutboxRegistro.class);

    @BeforeEach
    void setUp() {
        service = new VueloService(repository, outbox);
    }

    @Test
    void crearNormalizaCiudadesYConservaPrecioDecimal() {
        when(repository.save(any(Vuelo.class))).thenAnswer(invocation -> {
            Vuelo vuelo = invocation.getArgument(0);
            ReflectionTestUtils.setField(vuelo, "id", 1);
            return vuelo;
        });

        VueloResponse response = service.crear(request(" Quito ", " Guayaquil ", 100, 80));

        ArgumentCaptor<Vuelo> captor = ArgumentCaptor.forClass(Vuelo.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getOrigen()).isEqualTo("Quito");
        assertThat(captor.getValue().getDestino()).isEqualTo("Guayaquil");
        assertThat(response.id()).isEqualTo(1);
        assertThat(response.precio()).isEqualByComparingTo("125.50");
        assertThat(response.capacidadTotal()).isEqualTo(100);
        assertThat(response.capacidadDisponible()).isEqualTo(80);
    }

    @ParameterizedTest
    @CsvSource({"Quito,Quito", "QUITO,quito", "' Quito ',' quito '"})
    void rechazaOrigenIgualADestinoSinPersistir(String origen, String destino) {
        assertThatThrownBy(() -> service.crear(request(origen, destino, 100, 100)))
                .isInstanceOf(ReglaNegocioException.class)
                .hasMessageContaining("distintos");
        verifyNoInteractions(repository);
    }

    @Test
    void rechazaCapacidadDisponibleMayorQueTotalSinPersistir() {
        assertThatThrownBy(() -> service.crear(request("Quito", "Loja", 100, 101)))
                .isInstanceOf(ReglaNegocioException.class)
                .hasMessageContaining("capacidad");
        verifyNoInteractions(repository);
    }

    @Test
    void permiteCatalogarVueloSinDisponibilidadInformativa() {
        when(repository.save(any(Vuelo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        VueloResponse response = service.crear(request("Quito", "Loja", 100, 0));

        assertThat(response.capacidadDisponible()).isZero();
        assertThat(response.estado()).isEqualTo(EstadoVuelo.ACTIVO);
    }

    @Test
    void consultarDevuelveVueloExistente() {
        when(repository.findById(1)).thenReturn(Optional.of(vueloExistente()));

        VueloResponse response = service.consultar(1);

        assertThat(response.id()).isEqualTo(1);
        assertThat(response.origen()).isEqualTo("Quito");
        assertThat(response.fechaCreacion()).isEqualTo(LocalDateTime.of(2026, 1, 1, 10, 0));
    }

    @Test
    void consultarInexistenteLanzaErrorDeDominio() {
        when(repository.findById(9)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consultar(9))
                .isInstanceOf(VueloNoEncontradoException.class).hasMessageContaining("9");
    }

    @Test
    void listarSinFiltrosDevuelveCatalogo() {
        when(repository.buscar(null, null, null)).thenReturn(List.of(vueloExistente()));

        assertThat(service.listar(null, null, null)).hasSize(1);
        verify(repository).buscar(null, null, null);
    }

    @Test
    void buscarNormalizaFiltrosYConservaFecha() {
        LocalDate fecha = LocalDate.of(2027, 5, 10);
        when(repository.buscar("Quito", "Loja", fecha)).thenReturn(List.of());

        assertThat(service.listar(" Quito ", " Loja ", fecha)).isEmpty();
        verify(repository).buscar("Quito", "Loja", fecha);
    }

    @Test
    void permiteFiltrosParciales() {
        when(repository.buscar(null, "Loja", null)).thenReturn(List.of());

        assertThat(service.listar(null, "Loja", null)).isEmpty();
        verify(repository).buscar(null, "Loja", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void rechazaFiltrosEnBlanco(String filtro) {
        assertThatThrownBy(() -> service.listar(filtro, null, null))
                .isInstanceOf(ReglaNegocioException.class);
        assertThatThrownBy(() -> service.listar(null, filtro, null))
                .isInstanceOf(ReglaNegocioException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void rechazaFiltroDemasiadoLargo() {
        assertThatThrownBy(() -> service.listar("Q".repeat(101), null, null))
                .isInstanceOf(ReglaNegocioException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void actualizarConservaIdentidadYFechaCreacion() {
        Vuelo vuelo = vueloExistente();
        when(repository.findById(1)).thenReturn(Optional.of(vuelo));
        when(repository.save(vuelo)).thenReturn(vuelo);

        VueloResponse response = service.actualizar(1, request(" Cuenca ", " Loja ", 120, 100));

        assertThat(response.id()).isEqualTo(1);
        assertThat(response.fechaCreacion()).isEqualTo(LocalDateTime.of(2026, 1, 1, 10, 0));
        assertThat(response.origen()).isEqualTo("Cuenca");
        assertThat(response.destino()).isEqualTo("Loja");
        assertThat(response.capacidadTotal()).isEqualTo(120);
        verify(repository).save(vuelo);
        var orden = inOrder(repository, outbox);
        orden.verify(repository).save(vuelo);
        orden.verify(repository).flush();
        orden.verify(outbox).registrar(vuelo);
    }

    @Test
    void actualizarInexistenteNoCreaOtroVuelo() {
        when(repository.findById(9)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actualizar(9, request("Quito", "Loja", 100, 80)))
                .isInstanceOf(VueloNoEncontradoException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void actualizarTambienValidaRutaYCapacidad() {
        assertThatThrownBy(() -> service.actualizar(1, request("Quito", "QUITO", 100, 80)))
                .isInstanceOf(ReglaNegocioException.class);
        assertThatThrownBy(() -> service.actualizar(1, request("Quito", "Loja", 100, 101)))
                .isInstanceOf(ReglaNegocioException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void actualizarIdenticoNoEscribeNiGeneraEvento() {
        when(repository.findById(1)).thenReturn(Optional.of(vueloExistente()));
        service.actualizar(1, request(" Quito ", " Loja ", 100, 80));
        verify(repository, never()).save(any());
        verify(repository, never()).flush();
        verifyNoInteractions(outbox);
    }

    @Test
    void escalaDecimalDiferenteNoEsCambioDePrecio() {
        when(repository.findById(1)).thenReturn(Optional.of(vueloExistente()));
        VueloRequest request = new VueloRequest("Quito", "Loja", LocalDate.of(2027, 5, 10), LocalTime.of(9, 30),
                new BigDecimal("125.5"), EstadoVuelo.ACTIVO, 100, 80);
        service.actualizar(1, request);
        verify(repository, never()).save(any());
        verifyNoInteractions(outbox);
    }

    private VueloRequest request(String origen, String destino, int total, int disponible) {
        return new VueloRequest(origen, destino, LocalDate.of(2027, 5, 10), LocalTime.of(9, 30),
                new BigDecimal("125.50"), EstadoVuelo.ACTIVO, total, disponible);
    }

    private Vuelo vueloExistente() {
        Vuelo vuelo = new Vuelo("Quito", "Loja", LocalDate.of(2027, 5, 10), LocalTime.of(9, 30),
                new BigDecimal("125.50"), EstadoVuelo.ACTIVO, 100, 80);
        ReflectionTestUtils.setField(vuelo, "id", 1);
        ReflectionTestUtils.setField(vuelo, "fechaCreacion", LocalDateTime.of(2026, 1, 1, 10, 0));
        return vuelo;
    }
}

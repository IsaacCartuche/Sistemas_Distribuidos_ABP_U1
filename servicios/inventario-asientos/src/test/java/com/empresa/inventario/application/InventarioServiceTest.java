package com.empresa.inventario.application;

import com.empresa.inventario.api.InventarioDto.*;
import com.empresa.inventario.config.InventarioConfig.Propiedades;
import com.empresa.inventario.domain.*;
import com.empresa.inventario.persistence.*;
import com.empresa.inventario.outbox.ExpiracionOutbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class InventarioServiceTest {
    private final AsientoRepository asientos = mock(AsientoRepository.class);
    private final RetencionRepository retenciones = mock(RetencionRepository.class);
    private final ExpiracionOutbox outbox = mock(ExpiracionOutbox.class);
    private final Clock reloj = Clock.fixed(Instant.parse("2027-01-01T12:00:00Z"), ZoneOffset.UTC);
    private final UUID token = UUID.randomUUID();
    private InventarioService service;
    private Asiento asiento;
    private Retencion retencion;

    @BeforeEach
    void preparar() {
        service = new InventarioService(asientos, retenciones, reloj, new Propiedades(60, 1000), outbox);
        asiento = new Asiento(1, "1A");
        ReflectionTestUtils.setField(asiento, "id", 1);
        retencion = new Retencion(token, 1, service.ahora().plusSeconds(60));
        when(asientos.findById(1)).thenReturn(Optional.of(asiento));
        when(retenciones.findById(token)).thenReturn(Optional.empty());
    }
    private void bloquearExistente() {
        asiento.bloquear(token, retencion.getExpiraEn());
        when(retenciones.findById(token)).thenReturn(Optional.of(retencion));
    }
    @Test
    void bloqueoAsociaTokenYTtlConfigurado() {
        RetencionResponse response = service.bloquear(1, token);
        assertThat(response.expiraEn()).isEqualTo(service.ahora().plusSeconds(60));
        assertThat(asiento.getEstado()).isEqualTo(EstadoAsiento.BLOQUEADO);
        assertThat(asiento.getBloqueoToken()).isEqualTo(token);
        verify(asientos).flush();
        verify(retenciones).save(any());
    }
    @Test
    void bloqueoDuplicadoNoRenuevaTtlNiEscribe() {
        bloquearExistente();
        assertThat(service.bloquear(1, token).expiraEn()).isEqualTo(retencion.getExpiraEn());
        verify(asientos, never()).flush();
        verify(retenciones, never()).save(any());
    }
    @Test
    void otroTokenNoPuedeBloquearAsientoOcupado() {
        bloquearExistente();
        assertThatThrownBy(() -> service.bloquear(1, UUID.randomUUID())).isInstanceOf(InventarioException.class);
    }
    @Test
    void confirmarValidaPropiedadYReserva() {
        bloquearExistente();
        assertThat(service.confirmar(1, token).estado()).isEqualTo(EstadoRetencion.CONFIRMADA);
        assertThat(asiento.getEstado()).isEqualTo(EstadoAsiento.RESERVADO);
        verifyNoInteractions(outbox);
        assertThat(asiento.getBloqueadoHasta()).isNull();
        assertThat(asiento.getBloqueoToken()).isEqualTo(token);
    }
    @Test
    void confirmarDuplicadoNoEscribe() {
        bloquearExistente();
        service.confirmar(1, token);
        clearInvocations(asientos);
        assertThat(service.confirmar(1, token).estado()).isEqualTo(EstadoRetencion.CONFIRMADA);
        verify(asientos, never()).flush();
    }
    @Test
    void bloqueoDuplicadoConfirmadoDevuelveResultadoSinCambiarReserva() {
        bloquearExistente();
        service.confirmar(1, token);
        assertThat(service.bloquear(1, token).estado()).isEqualTo(EstadoRetencion.CONFIRMADA);
        assertThat(asiento.getEstado()).isEqualTo(EstadoAsiento.RESERVADO);
    }
    @Test
    void noConfirmaEnLimiteExactoTtl() {
        retencion = new Retencion(token, 1, service.ahora());
        bloquearExistente();
        assertThatThrownBy(() -> service.confirmar(1, token)).hasMessageContaining("expirado");
        assertThat(asiento.getEstado()).isEqualTo(EstadoAsiento.BLOQUEADO);
    }
    @Test
    void tokenDeOtroAsientoNoPuedeConfirmarNiLiberarNiBloquear() {
        retencion = new Retencion(token, 2, service.ahora().plusMinutes(1));
        bloquearExistente();
        assertThatThrownBy(() -> service.confirmar(1, token)).hasMessageContaining("otro asiento");
        assertThatThrownBy(() -> service.liberar(1, token)).hasMessageContaining("otro asiento");
        assertThatThrownBy(() -> service.bloquear(1, token)).hasMessageContaining("otro asiento");
    }
    @Test
    void tokenDesconocidoNoLiberaNiConfirma() {
        assertThatThrownBy(() -> service.liberar(1, token)).hasMessageContaining("no encontrada");
        assertThatThrownBy(() -> service.confirmar(1, token)).hasMessageContaining("no encontrada");
    }
    @Test
    void liberarActivoEsIdempotente() {
        bloquearExistente();
        assertThat(service.liberar(1, token).estado()).isEqualTo(EstadoRetencion.LIBERADA);
        assertThat(asiento.getEstado()).isEqualTo(EstadoAsiento.DISPONIBLE);
        assertThat(asiento.getBloqueoToken()).isNull();
        clearInvocations(asientos);
        service.liberar(1, token);
        verify(asientos, never()).flush();
    }
    @Test
    void liberarExpiradoRegistraExpiracion() {
        retencion = new Retencion(token, 1, service.ahora().minusSeconds(1));
        bloquearExistente();
        assertThat(service.liberar(1, token).estado()).isEqualTo(EstadoRetencion.EXPIRADA);
        verify(outbox).registrar(asiento, retencion);
    }
    @Test
    void liberarConfirmadoNoCancelaReserva() {
        bloquearExistente();
        service.confirmar(1, token);
        assertThatThrownBy(() -> service.liberar(1, token)).hasMessageContaining("reservado");
        assertThat(asiento.getEstado()).isEqualTo(EstadoAsiento.RESERVADO);
    }
    @ParameterizedTest
    @EnumSource(value = EstadoRetencion.class, names = {"LIBERADA", "EXPIRADA"})
    void reintentoTerminalNoTocaBloqueoNuevo(EstadoRetencion estado) {
        bloquearExistente();
        retencion.terminar(estado);
        UUID nuevo = UUID.randomUUID();
        asiento.bloquear(nuevo, service.ahora().plusSeconds(60));
        service.liberar(1, token);
        assertThat(asiento.getBloqueoToken()).isEqualTo(nuevo);
        assertThatThrownBy(() -> service.bloquear(1, token)).isInstanceOf(InventarioException.class);
        assertThatThrownBy(() -> service.confirmar(1, token)).isInstanceOf(InventarioException.class);
    }
    @Test
    void bloqueoNuevoRecuperaExpiradoSinScheduler() {
        retencion = new Retencion(token, 1, service.ahora());
        bloquearExistente();
        UUID nuevo = UUID.randomUUID();
        service.bloquear(1, nuevo);
        assertThat(retencion.getEstado()).isEqualTo(EstadoRetencion.EXPIRADA);
        assertThat(asiento.getBloqueoToken()).isEqualTo(nuevo);
    }
    @Test
    void expirarRevalidaEstadoYNoLiberaConfirmadoONuevoBloqueo() {
        bloquearExistente();
        service.expirarUno(1);
        assertThat(asiento.getEstado()).isEqualTo(EstadoAsiento.BLOQUEADO);
        service.confirmar(1, token);
        service.expirarUno(1);
        assertThat(asiento.getEstado()).isEqualTo(EstadoAsiento.RESERVADO);
    }
    @Test
    void expirarExactamenteEnLimiteLiberaUnaSolaVez() {
        retencion = new Retencion(token, 1, service.ahora());
        bloquearExistente();
        service.expirarUno(1);
        service.expirarUno(1);
        assertThat(retencion.getEstado()).isEqualTo(EstadoRetencion.EXPIRADA);
        assertThat(asiento.getEstado()).isEqualTo(EstadoAsiento.DISPONIBLE);
        verify(asientos, times(1)).flush();
        verify(outbox, times(1)).registrar(asiento, retencion);
    }
    @Test
    void inicializarNormalizaDeduplicaYOrdena() {
        when(asientos.findByVueloIdOrderByNumeroAsiento(1)).thenReturn(List.of());
        service.inicializar(1, new InicializarRequest(List.of("2b", "1a", "1A")));
        var orden = inOrder(asientos);
        orden.verify(asientos).insertarSiAusente(1, "1A");
        orden.verify(asientos).insertarSiAusente(1, "2B");
        verify(asientos, times(2)).insertarSiAusente(any(), any());
    }
    @Test
    void consultarInexistenteEs404() {
        assertThatThrownBy(() -> service.consultar(9)).isInstanceOfSatisfying(InventarioException.class,
                e -> assertThat(e.getStatus()).isEqualTo(404));
    }
    @Test
    void disponibilidadIncluyeExpiradosPeroNuncaReservados() {
        assertThat(asiento.disponible(service.ahora())).isTrue();
        asiento.bloquear(token, service.ahora());
        assertThat(asiento.disponible(service.ahora())).isTrue();
        asiento.confirmar();
        assertThat(asiento.disponible(service.ahora())).isFalse();
    }
}

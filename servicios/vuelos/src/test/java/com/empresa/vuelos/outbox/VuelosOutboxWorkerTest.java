package com.empresa.vuelos.outbox;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VuelosOutboxWorkerTest {
    private final VuelosOutboxRepository repo = mock(VuelosOutboxRepository.class);
    private final PublicadorVuelos publicador = mock(PublicadorVuelos.class);
    private final VuelosOutboxWorker worker = new VuelosOutboxWorker(repo, publicador, new VuelosOutboxConfig.Propiedades(100, 5, 100));
    private final VuelosOutboxRepository.Evento evento = PublicadorVuelosTest.valido();
    @Test void sinPendientesNoUsaBroker() {
        when(repo.reclamar()).thenReturn(Optional.empty());
        assertThat(worker.publicarUno()).isFalse(); verifyNoInteractions(publicador);
    }
    @Test void ackPrecedeMarcaEnviado() {
        when(repo.reclamar()).thenReturn(Optional.of(evento)); worker.publicarUno();
        var orden = inOrder(publicador, repo); orden.verify(publicador).publicar(evento); orden.verify(repo).enviado(evento.eventId());
    }
    @Test void falloTemporalNoMarcaEnviado() {
        when(repo.reclamar()).thenReturn(Optional.of(evento));
        doThrow(new PublicadorVuelos.FalloPublicacion("SIN_DESTINO")).when(publicador).publicar(evento);
        worker.publicarUno(); verify(repo).reintentar(evento.eventId(), 5, "SIN_DESTINO"); verify(repo, never()).enviado(any());
    }
    @Test void errorDatosNoSeReintenta() {
        when(repo.reclamar()).thenReturn(Optional.of(evento));
        doThrow(new PublicadorVuelos.FalloDatos("JSON_INVALIDO")).when(publicador).publicar(evento);
        worker.publicarUno(); verify(repo).errorDatos(evento.eventId(), "JSON_INVALIDO");
        verify(repo, never()).reintentar(any(), anyLong(), any()); verify(repo, never()).enviado(any());
    }
    @Test void falloDeBaseTrasAckProvocaRollback() {
        when(repo.reclamar()).thenReturn(Optional.of(evento));
        doThrow(new IllegalStateException("DB")).when(repo).enviado(evento.eventId());
        assertThatThrownBy(worker::publicarUno).hasMessage("DB");
        verify(repo, never()).reintentar(any(), anyLong(), any());
    }
}

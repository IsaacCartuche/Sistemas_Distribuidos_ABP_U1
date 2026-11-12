package com.empresa.inventario.outbox;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxWorkerTest {
    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final PublicadorExpiracion publicador = mock(PublicadorExpiracion.class);
    private final OutboxWorker worker = new OutboxWorker(repository, publicador, new OutboxConfig.Propiedades(100, 5, 100));
    private final UUID eventId = UUID.randomUUID();

    @Test
    void sinPendientesNoContactaBroker() {
        when(repository.reclamarPendiente()).thenReturn(Optional.empty());
        assertThat(worker.publicarUno()).isFalse();
        verifyNoInteractions(publicador);
    }
    @Test
    void soloMarcaDespuesDeConfirmar() {
        var evento = new OutboxRepository.Evento(eventId, UUID.randomUUID(), UUID.randomUUID(), "{}", 0);
        when(repository.reclamarPendiente()).thenReturn(Optional.of(evento));
        assertThat(worker.publicarUno()).isTrue();
        var orden = inOrder(publicador, repository);
        orden.verify(publicador).publicar(evento);
        orden.verify(repository).enviado(eventId);
    }
    @Test
    void falloConservaIdYAumentaEsperaAcotada() {
        var evento = new OutboxRepository.Evento(eventId, UUID.randomUUID(), UUID.randomUUID(), "{}", 30);
        when(repository.reclamarPendiente()).thenReturn(Optional.of(evento));
        doThrow(new PublicadorExpiracion.FalloPublicacion("SIN_DESTINO")).when(publicador).publicar(evento);
        worker.publicarUno();
        verify(repository).reintentar(eventId, 300, "SIN_DESTINO");
        verify(repository, never()).enviado(any());
    }
    @Test
    void falloDeBaseTrasPublicarSePropagaParaRollback() {
        var evento = new OutboxRepository.Evento(eventId, UUID.randomUUID(), UUID.randomUUID(), "{}", 0);
        when(repository.reclamarPendiente()).thenReturn(Optional.of(evento));
        doThrow(new IllegalStateException("DB")).when(repository).enviado(eventId);
        assertThatThrownBy(worker::publicarUno).hasMessage("DB");
        verify(repository, never()).reintentar(any(), anyLong(), any());
    }

    @Test
    void datosCorruptosSeRetienenSinReintentoTemporal() {
        var evento = new OutboxRepository.Evento(eventId, UUID.randomUUID(), UUID.randomUUID(), "{", 0);
        when(repository.reclamarPendiente()).thenReturn(Optional.of(evento));
        doThrow(new PublicadorExpiracion.FalloDatos("JSON_INVALIDO")).when(publicador).publicar(evento);
        assertThat(worker.publicarUno()).isTrue();
        verify(repository).errorDatos(eventId, "JSON_INVALIDO");
        verify(repository, never()).reintentar(any(), anyLong(), any());
        verify(repository, never()).enviado(any());
    }
}

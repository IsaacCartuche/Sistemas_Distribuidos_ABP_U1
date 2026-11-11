package com.empresa.reservas.service;

import com.empresa.reservas.entity.OutboxEvento;
import com.empresa.reservas.repository.OutboxEventoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxProcesamientoServiceTest {

        private OutboxReclamacionService reclamacionService;
        private OutboxRabbitPublisher publisher;
        private OutboxEventoRepository repository;
        private OutboxProcesamientoService service;

        @BeforeEach
        void configurar() {
                reclamacionService = mock(OutboxReclamacionService.class);
                publisher = mock(OutboxRabbitPublisher.class);
                repository = mock(OutboxEventoRepository.class);

                service = new OutboxProcesamientoService(
                                reclamacionService, publisher, repository);
        }

        @Test
        void debeMarcarPublicadoCuandoRabbitMQConfirma() {
                OutboxEvento evento = new OutboxEvento();
                evento.setId(UUID.randomUUID());

                when(reclamacionService.reclamarPendientes(any(UUID.class)))
                                .thenReturn(List.of(evento));
                when(publisher.publicar(evento)).thenReturn(true);

                service.procesarPendientes();

                verify(repository).marcarPublicado(
                                eq(evento.getId()),
                                any(UUID.class),
                                any());
                verify(repository, never()).devolverAPendiente(
                                any(UUID.class), any(UUID.class));
        }

        @Test
        void debeDevolverAPendienteCuandoRabbitMQFalla() {
                OutboxEvento evento = new OutboxEvento();
                evento.setId(UUID.randomUUID());

                when(reclamacionService.reclamarPendientes(any(UUID.class)))
                                .thenReturn(List.of(evento));
                when(publisher.publicar(evento)).thenReturn(false);

                service.procesarPendientes();

                verify(repository).devolverAPendiente(
                                eq(evento.getId()),
                                any(UUID.class));
                verify(repository, never()).marcarPublicado(
                                any(UUID.class), any(UUID.class), any());
        }

        @Test
        void debeDevolverAPendienteCuandoPublicadorLanzaExcepcion() {
                OutboxEvento evento = new OutboxEvento();
                evento.setId(UUID.randomUUID());

                when(reclamacionService.reclamarPendientes(any(UUID.class)))
                                .thenReturn(List.of(evento));

                when(publisher.publicar(evento))
                                .thenThrow(new RuntimeException("RabbitMQ no disponible"));

                service.procesarPendientes();

                verify(repository).devolverAPendiente(
                                eq(evento.getId()),
                                any(UUID.class));

                verify(repository, never()).marcarPublicado(
                                any(UUID.class),
                                any(UUID.class),
                                any());
        }

        @Test
        void debeContinuarProcesandoSiPostgreSQLFallaEnUnEvento() {

                OutboxEvento evento1 = new OutboxEvento();
                evento1.setId(UUID.randomUUID());

                OutboxEvento evento2 = new OutboxEvento();
                evento2.setId(UUID.randomUUID());

                when(reclamacionService.reclamarPendientes(any(UUID.class)))
                                .thenReturn(List.of(evento1, evento2));

                when(publisher.publicar(evento1)).thenReturn(true);
                when(publisher.publicar(evento2)).thenReturn(true);

                when(repository.marcarPublicado(
                                eq(evento1.getId()),
                                any(UUID.class),
                                any())).thenThrow(new RuntimeException("PostgreSQL no disponible"));

                when(repository.marcarPublicado(
                                eq(evento2.getId()),
                                any(UUID.class),
                                any())).thenReturn(1);

                service.procesarPendientes();

                verify(publisher).publicar(evento1);
                verify(publisher).publicar(evento2);

                verify(repository).marcarPublicado(
                                eq(evento2.getId()),
                                any(UUID.class),
                                any());
        }
}

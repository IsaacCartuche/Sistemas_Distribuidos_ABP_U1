package com.empresa.reservas.integration;

import com.empresa.reservas.entity.OutboxEvento;
import com.empresa.reservas.repository.OutboxEventoRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class OutboxConcurrenciaIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OutboxEventoRepository repository;

    @Test
    void skipLockedDebeEvitarSeleccionConcurrente() throws Exception {

        UUID eventoId = UUID.randomUUID();

        OutboxEvento evento = new OutboxEvento();
        evento.setId(eventoId);
        evento.setCorrelationId(UUID.randomUUID());
        evento.setTipo("test.concurrencia");
        evento.setExchange("vuelos.events");
        evento.setRoutingKey("test.concurrencia");
        evento.setPayload("{}");

        repository.saveAndFlush(evento);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch bloqueoAdquirido = new CountDownLatch(1);
        CountDownLatch liberarBloqueo = new CountDownLatch(1);

        try {
            Future<Boolean> primera = executor.submit(() -> {
                try (Connection conexion = dataSource.getConnection()) {
                    conexion.setAutoCommit(false);

                    try {
                        boolean encontrado = consultarEvento(
                                conexion, eventoId, false
                        );

                        bloqueoAdquirido.countDown();

                        if (!liberarBloqueo.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "Tiempo agotado esperando liberar bloqueo"
                            );
                        }

                        return encontrado;
                    } finally {
                        conexion.rollback();
                    }
                }
            });

            assertTrue(
                    bloqueoAdquirido.await(10, TimeUnit.SECONDS),
                    "La primera transaccion no adquirio el bloqueo"
            );

            Future<Boolean> segunda = executor.submit(() -> {
                try (Connection conexion = dataSource.getConnection()) {
                    conexion.setAutoCommit(false);

                    try {
                        return consultarEvento(
                                conexion, eventoId, true
                        );
                    } finally {
                        conexion.rollback();
                    }
                }
            });

            assertFalse(
                    segunda.get(10, TimeUnit.SECONDS),
                    "SKIP LOCKED no debe seleccionar el evento bloqueado"
            );

            liberarBloqueo.countDown();

            assertTrue(
                    primera.get(10, TimeUnit.SECONDS),
                    "La primera transaccion debe encontrar el evento"
            );

        } finally {
            liberarBloqueo.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            repository.deleteById(eventoId);
        }
    }

    private boolean consultarEvento(
            Connection conexion,
            UUID eventoId,
            boolean skipLocked
    ) throws Exception {

        String sql = """
                SELECT id
                FROM outbox_eventos
                WHERE id = ?
                FOR UPDATE
                """ + (skipLocked ? " SKIP LOCKED" : "");

        try (PreparedStatement statement =
                     conexion.prepareStatement(sql)) {

            statement.setObject(1, eventoId);

            try (ResultSet resultado = statement.executeQuery()) {
                return resultado.next();
            }
        }
    }
}


package com.empresa.inventario.outbox;

import com.empresa.inventario.inbox.EventoReserva;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;

/** Registra resultados en LA MISMA outbox; nunca envía mensajes durante la transacción de negocio. */
@Service
public class ResultadosSagaOutbox {
    private final OutboxRepository repository;
    private final ObjectMapper mapper;
    private final ValidadorExpiracion validador;
    private final Clock reloj;
    public ResultadosSagaOutbox(OutboxRepository repository, ObjectMapper mapper, ValidadorExpiracion validador, Clock reloj) {
        this.repository = repository; this.mapper = mapper; this.validador = validador; this.reloj = reloj;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(EventoReserva solicitud, String tipo, Integer asientoId, String motivo) {
        try {
            UUID id = UUID.randomUUID();
            var evento = mapper.createObjectNode();
            evento.put("eventId", id.toString());
            evento.put("correlationId", solicitud.correlationId().toString());
            evento.put("timestamp", reloj.instant().toString());
            evento.put("tipo", tipo);
            var payload = evento.putObject("payload");
            payload.set("reservaId", solicitud.reservaId());
            payload.put("vueloId", solicitud.vueloId());
            if (asientoId == null) payload.putNull("asientoId"); else payload.put("asientoId", asientoId);
            payload.put("asiento", solicitud.asiento());
            payload.put("token", solicitud.token().toString());
            if (motivo != null) payload.put("motivo", motivo);
            String mensaje = mapper.writeValueAsString(evento);
            validador.validar(new OutboxRepository.Evento(id, solicitud.correlationId(), solicitud.token(), mensaje, 0, tipo));
            String clave = null;
            if (motivo != null) {
                // Identidad del intento, NO del mensaje/fecha/motivo: reintentos semánticos no crean otro rechazo.
                var identidad = mapper.createObjectNode();
                identidad.set("reservaId", solicitud.reservaId());
                identidad.put("correlationId", solicitud.correlationId().toString());
                identidad.put("token", solicitud.token().toString());
                identidad.put("vueloId", solicitud.vueloId());
                identidad.put("asiento", solicitud.asiento());
                if (solicitud.asientoId() != null) identidad.put("asientoId", solicitud.asientoId());
                clave = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(identidad)));
            }
            repository.registrar(id, solicitud.token(), mensaje, solicitud.correlationId(), tipo, clave);
        } catch (JsonProcessingException | java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("No se pudo registrar resultado Saga", exception);
        }
    }
}

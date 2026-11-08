package com.empresa.vuelos.outbox;

import com.empresa.vuelos.domain.Vuelo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

@Service
public class VueloOutboxRegistro {
    private final VuelosOutboxRepository repository;
    private final ObjectMapper mapper;
    private final ValidadorEventoVuelo validador;
    private final EntityManager entityManager;
    private final Clock reloj;
    public VueloOutboxRegistro(VuelosOutboxRepository repository, ObjectMapper mapper, ValidadorEventoVuelo validador,
                               EntityManager entityManager, Clock reloj) {
        this.repository = repository; this.mapper = mapper; this.validador = validador;
        this.entityManager = entityManager; this.reloj = reloj;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(Vuelo vuelo) {
        // Tras el flush, lee la representación realmente persistida (incluida precisión de TIME/NUMERIC).
        entityManager.refresh(vuelo);
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID(); // Traza de esta operación de catálogo, NO una reserva ficticia.
        var evento = mapper.createObjectNode();
        evento.put("eventId", eventId.toString()); evento.put("correlationId", correlationId.toString());
        evento.put("timestamp", reloj.instant().toString()); evento.put("tipo", "vuelos.actualizados");
        var payload = evento.putObject("payload");
        payload.put("vueloId", vuelo.getId()); payload.put("revision", vuelo.getVersion());
        payload.put("origen", vuelo.getOrigen()); payload.put("destino", vuelo.getDestino());
        payload.put("fechaSalida", vuelo.getFechaSalida().toString()); payload.put("horaSalida", vuelo.getHoraSalida().toString());
        payload.put("precio", vuelo.getPrecio()); payload.put("estado", vuelo.getEstado().name());
        payload.put("capacidadTotal", vuelo.getCapacidadTotal());
        try {
            var fila = new VuelosOutboxRepository.Evento(eventId, correlationId, vuelo.getId(), vuelo.getVersion(),
                    mapper.writeValueAsString(evento), 0);
            validador.validar(fila);
            repository.registrar(fila);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("No se pudo serializar actualización de vuelo", exception);
        }
    }
}

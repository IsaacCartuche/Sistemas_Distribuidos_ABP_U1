package com.empresa.inventario.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxWorker {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxWorker.class);
    private final OutboxRepository repository;
    private final PublicadorExpiracion publicador;
    private final OutboxConfig.Propiedades propiedades;
    public OutboxWorker(OutboxRepository repository, PublicadorExpiracion publicador, OutboxConfig.Propiedades propiedades) {
        this.repository = repository;
        this.publicador = publicador;
        this.propiedades = propiedades;
    }

    /** Bloquea SOLO una fila outbox durante el envío acotado; no bloquea asientos ni retenciones. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 40)
    public boolean publicarUno() {
        var candidato = repository.reclamarPendiente();
        if (candidato.isEmpty()) return false;
        var evento = candidato.get();
        try {
            publicador.publicar(evento);
        } catch (PublicadorExpiracion.FalloDatos exception) {
            repository.errorDatos(evento.eventId(), exception.getMessage());
            LOG.error("Evento {} retenido por error permanente de datos: {}", evento.eventId(), exception.getMessage());
            return true;
        } catch (PublicadorExpiracion.FalloPublicacion exception) {
            long espera = Math.min(300L, propiedades.reintentoSegundos() * (1L << Math.min(evento.intentos(), 6)));
            repository.reintentar(evento.eventId(), espera, exception.getMessage());
            LOG.warn("Evento {} pendiente de reintento: {}", evento.eventId(), exception.getMessage());
            return true;
        }
        // Un fallo DB aquí se propaga: rollback y posible reentrega con el MISMO eventId.
        repository.enviado(evento.eventId());
        return true;
    }
}

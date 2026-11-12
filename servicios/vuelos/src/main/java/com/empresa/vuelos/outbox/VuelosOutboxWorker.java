package com.empresa.vuelos.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VuelosOutboxWorker {
    private static final Logger LOG = LoggerFactory.getLogger(VuelosOutboxWorker.class);
    private final VuelosOutboxRepository repository;
    private final PublicadorVuelos publicador;
    private final VuelosOutboxConfig.Propiedades propiedades;
    public VuelosOutboxWorker(VuelosOutboxRepository repository, PublicadorVuelos publicador, VuelosOutboxConfig.Propiedades propiedades) {
        this.repository = repository; this.publicador = publicador; this.propiedades = propiedades;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 40)
    public boolean publicarUno() {
        var candidato = repository.reclamar();
        if (candidato.isEmpty()) return false;
        var evento = candidato.get();
        try { publicador.publicar(evento); }
        catch (PublicadorVuelos.FalloDatos exception) {
            repository.errorDatos(evento.eventId(), exception.getMessage());
            LOG.error("Evento {} retenido por error de datos: {}", evento.eventId(), exception.getMessage());
            return true;
        } catch (PublicadorVuelos.FalloPublicacion exception) {
            long segundos = Math.min(300L, propiedades.reintentoSegundos() * (1L << Math.min(evento.intentos(), 6)));
            repository.reintentar(evento.eventId(), segundos, exception.getMessage());
            LOG.warn("Evento {} pendiente: {}", evento.eventId(), exception.getMessage());
            return true;
        }
        repository.enviado(evento.eventId());
        return true;
    }
}

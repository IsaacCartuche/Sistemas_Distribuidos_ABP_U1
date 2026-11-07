package com.empresa.inventario.outbox;

/** Un resultado correcto significa ACK del broker sin basic.return, no ACK del consumidor. */
public interface PublicadorExpiracion {
    void publicar(OutboxRepository.Evento evento);

    class FalloPublicacion extends RuntimeException {
        public FalloPublicacion(String motivo) { super(motivo); }
    }

    class FalloDatos extends FalloPublicacion {
        public FalloDatos(String motivo) { super(motivo); }
    }
}

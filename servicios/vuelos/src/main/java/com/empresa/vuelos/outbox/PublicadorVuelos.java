package com.empresa.vuelos.outbox;

public interface PublicadorVuelos {
    void publicar(VuelosOutboxRepository.Evento evento);
    class FalloPublicacion extends RuntimeException {
        public FalloPublicacion(String motivo) { super(motivo); }
    }
    class FalloDatos extends FalloPublicacion {
        public FalloDatos(String motivo) { super(motivo); }
    }
}

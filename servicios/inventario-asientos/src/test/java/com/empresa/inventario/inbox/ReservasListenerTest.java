package com.empresa.inventario.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservasListenerTest {
    private final ProcesadorReservas procesador = mock(ProcesadorReservas.class);
    private final ReservasListener listener = new ReservasListener(new ValidadorReserva(new ObjectMapper()), procesador);
    @Test
    void delegaAlProcesadorTransaccional() {
        listener.recibir(mensaje(ValidadorReservaTest.valido()));
        verify(procesador).procesar(any(EventoReserva.class));
    }
    @Test
    void invalidoNoInvocaNegocio() {
        assertThatThrownBy(() -> listener.recibir(mensaje("{"))).isInstanceOf(EventoReservaInvalido.class);
        verifyNoInteractions(procesador);
    }
    private Message mensaje(String body) {
        var props = new MessageProperties();
        props.setReceivedRoutingKey("reserva.creada");
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }
}

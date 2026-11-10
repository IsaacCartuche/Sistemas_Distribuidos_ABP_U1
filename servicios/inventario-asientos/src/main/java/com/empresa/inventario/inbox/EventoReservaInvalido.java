package com.empresa.inventario.inbox;

public class EventoReservaInvalido extends RuntimeException {
    public EventoReservaInvalido(String codigo) { super(codigo); }
}

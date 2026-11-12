package com.empresa.inventario.application;

public class InventarioException extends RuntimeException {
    private final int status;
    public InventarioException(int status, String mensaje) {
        super(mensaje);
        this.status = status;
    }
    public int getStatus() { return status; }
}

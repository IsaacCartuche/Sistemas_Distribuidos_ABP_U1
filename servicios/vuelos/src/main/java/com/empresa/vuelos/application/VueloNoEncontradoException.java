package com.empresa.vuelos.application;

public class VueloNoEncontradoException extends RuntimeException {
    public VueloNoEncontradoException(Integer id) {
        super("No existe el vuelo con ID " + id);
    }
}

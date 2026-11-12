package com.empresa.reservas.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ReservaExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> manejarArgumentoInvalido(
            IllegalArgumentException ex) {

        String mensaje = ex.getMessage();

        if ("Clave de idempotencia reutilizada con datos diferentes"
                .equals(mensaje)) {

            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(Map.of(
                            "error", "CONFLICTO_IDEMPOTENCIA",
                            "mensaje", mensaje
                    ));
        }

        return ResponseEntity
                .badRequest()
                .body(Map.of(
                        "error", "DATOS_INVALIDOS",
                        "mensaje", mensaje != null
                                ? mensaje
                                : "Solicitud invalida"
                ));
    }
}

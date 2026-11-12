-- Aditiva. Aplicación MANUAL, después de las migraciones outbox. No modifica datos previos.
BEGIN;
CREATE TABLE IF NOT EXISTS inventario_inbox (
    consumidor VARCHAR(80) NOT NULL,
    event_id UUID NOT NULL,
    tipo VARCHAR(30) NOT NULL CHECK (tipo IN ('reserva.creada', 'reserva.cancelada')),
    huella CHAR(64) NOT NULL,
    resultado VARCHAR(80),
    recibido_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    procesado_en TIMESTAMPTZ,
    PRIMARY KEY(consumidor, event_id)
);
CREATE TABLE IF NOT EXISTS inventario_solicitudes (
    -- JSON escalar canónico: 123 y "123" NO se consideran la misma reserva.
    reserva_clave TEXT PRIMARY KEY,
    correlation_id UUID NOT NULL,
    token UUID NOT NULL UNIQUE,
    vuelo_id INTEGER NOT NULL CHECK (vuelo_id > 0),
    numero_asiento VARCHAR(10) NOT NULL,
    asiento_id INTEGER REFERENCES asientos(id),
    estado VARCHAR(20) NOT NULL CHECK (estado IN ('RECIBIDA', 'BLOQUEADA', 'CANCELADA', 'RECHAZADA')),
    creado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMIT;

-- Base de datos: db_pagos
-- Servicio: Pagos

CREATE TABLE IF NOT EXISTS pagos (
    id SERIAL PRIMARY KEY,
    reserva_id INTEGER NOT NULL,
    monto DECIMAL(10,2) NOT NULL,
    estado VARCHAR(20) DEFAULT 'PENDIENTE',
    metodo_pago VARCHAR(50),
    transaccion_externa VARCHAR(100),
    fecha_procesamiento TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    idempotency_key VARCHAR(100) UNIQUE,
    version INTEGER DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_pagos_estado ON pagos(estado);
CREATE INDEX IF NOT EXISTS idx_pagos_reserva ON pagos(reserva_id);

CREATE TABLE IF NOT EXISTS pago_saga (
 correlation_id UUID PRIMARY KEY, token UUID NOT NULL, reserva_id INTEGER NOT NULL UNIQUE CHECK(reserva_id>0),
 monto DECIMAL(10,2) CHECK(monto>0), estado VARCHAR(20) NOT NULL CHECK(estado IN ('PENDIENTE','EXITOSO','FALLIDO','CANCELADO','REEMBOLSADO')),
 creado BOOLEAN NOT NULL DEFAULT FALSE, bloqueado BOOLEAN NOT NULL DEFAULT FALSE,
 cancelado BOOLEAN NOT NULL DEFAULT FALSE, version INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS pago_intentos (
 id BIGSERIAL PRIMARY KEY, correlation_id UUID NOT NULL REFERENCES pago_saga(correlation_id),
 operacion VARCHAR(20) NOT NULL, resultado VARCHAR(20) NOT NULL,
 fecha TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(correlation_id,operacion)
);
CREATE TABLE IF NOT EXISTS saga_inbox (
 event_id UUID PRIMARY KEY, tipo VARCHAR(100) NOT NULL,
 correlation_id UUID NOT NULL, recibido_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS outbox_eventos (
    id UUID PRIMARY KEY,
    correlation_id UUID NOT NULL,
    tipo VARCHAR(100) NOT NULL,
    exchange VARCHAR(100) NOT NULL,
    routing_key VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    estado VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
    fecha_creacion TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fecha_publicacion TIMESTAMP,
    intentos INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_outbox_estado_fecha
ON outbox_eventos (estado, fecha_creacion);


-- Control de procesamiento para Transactional Outbox

ALTER TABLE outbox_eventos
    ADD COLUMN IF NOT EXISTS fecha_reclamo TIMESTAMP;

ALTER TABLE outbox_eventos
    ADD COLUMN IF NOT EXISTS reclamado_por UUID;

-- Facilita identificar eventos en proceso
-- que necesiten recuperación.

CREATE INDEX IF NOT EXISTS idx_outbox_reclamo
    ON outbox_eventos (estado, fecha_reclamo);

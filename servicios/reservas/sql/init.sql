-- Base de datos: db_reservas
-- Servicio: Reservas

CREATE TABLE IF NOT EXISTS reservas (
    id SERIAL PRIMARY KEY,
    vuelo_id INTEGER NOT NULL,
    usuario_id INTEGER NOT NULL,
    asiento VARCHAR(10) NOT NULL,
    estado VARCHAR(20) DEFAULT 'PENDIENTE',
    fecha_creacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    fecha_expiracion TIMESTAMP,
    total DECIMAL(10,2) NOT NULL,
    idempotency_key VARCHAR(100) UNIQUE,
    version INTEGER DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_reservas_estado ON reservas(estado);
CREATE INDEX IF NOT EXISTS idx_reservas_idempotency ON reservas(idempotency_key);


-- Migración V1: Campos para la Saga de Reservas
-- Base de datos: db_reservas

ALTER TABLE reservas
    ADD COLUMN IF NOT EXISTS correlation_id UUID,
    ADD COLUMN IF NOT EXISTS token UUID,
    ADD COLUMN IF NOT EXISTS asiento_id INTEGER;

CREATE UNIQUE INDEX IF NOT EXISTS idx_reservas_correlation_id
    ON reservas(correlation_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_reservas_token
    ON reservas(token);

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

CREATE TABLE IF NOT EXISTS saga_inbox (
 event_id UUID PRIMARY KEY, tipo VARCHAR(100) NOT NULL,
 correlation_id UUID NOT NULL, recibido_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE reservas ADD COLUMN IF NOT EXISTS asiento_bloqueado BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE reservas ADD COLUMN IF NOT EXISTS pago_exitoso BOOLEAN NOT NULL DEFAULT FALSE;

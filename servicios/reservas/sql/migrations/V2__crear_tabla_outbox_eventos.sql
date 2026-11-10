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

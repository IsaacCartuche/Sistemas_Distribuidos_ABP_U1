-- MANUAL sobre db_vuelos. No elimina filas ni cambia datos existentes del catálogo.
-- El DEFAULT establece la revisión inicial 0 para registros anteriores.
BEGIN;
ALTER TABLE vuelos ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0);
CREATE TABLE IF NOT EXISTS vuelos_outbox (
    event_id UUID PRIMARY KEY,
    correlation_id UUID NOT NULL,
    vuelo_id INTEGER NOT NULL REFERENCES vuelos(id),
    revision INTEGER NOT NULL CHECK (revision > 0),
    tipo VARCHAR(50) NOT NULL CHECK (tipo = 'vuelos.actualizados'),
    mensaje TEXT NOT NULL,
    estado VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE' CHECK (estado IN ('PENDIENTE', 'ENVIADO', 'ERROR_DATOS')),
    intentos INTEGER NOT NULL DEFAULT 0 CHECK (intentos >= 0),
    creado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    siguiente_intento TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    enviado_en TIMESTAMPTZ,
    ultimo_error VARCHAR(100),
    UNIQUE(vuelo_id, revision)
);
CREATE INDEX IF NOT EXISTS idx_vuelos_outbox_pendientes ON vuelos_outbox(siguiente_intento, creado_en)
    WHERE estado = 'PENDIENTE';
COMMIT;

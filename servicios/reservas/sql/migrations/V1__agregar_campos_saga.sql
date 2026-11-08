
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

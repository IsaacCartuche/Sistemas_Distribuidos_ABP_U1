
-- Control de procesamiento para Transactional Outbox

ALTER TABLE outbox_eventos
    ADD COLUMN IF NOT EXISTS fecha_reclamo TIMESTAMP;

ALTER TABLE outbox_eventos
    ADD COLUMN IF NOT EXISTS reclamado_por UUID;

-- Facilita identificar eventos en proceso
-- que necesiten recuperación.

CREATE INDEX IF NOT EXISTS idx_outbox_reclamo
    ON outbox_eventos (estado, fecha_reclamo);

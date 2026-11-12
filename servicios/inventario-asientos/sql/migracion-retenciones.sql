-- Migración MANUAL del esquema original. Ejecutar con el servicio detenido y ON_ERROR_STOP=1.
-- No elimina ni sobrescribe datos. Si hay bloqueos heredados, primero reconciliarlos con su dueño.
BEGIN;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM asientos WHERE estado = 'BLOQUEADO') THEN
        RAISE EXCEPTION 'Hay bloqueos heredados: reconciliar antes de migrar; no se inventan tokens';
    END IF;
END $$;

ALTER TABLE asientos ADD COLUMN IF NOT EXISTS bloqueo_token UUID;
ALTER TABLE asientos ALTER COLUMN estado SET NOT NULL;
ALTER TABLE asientos ALTER COLUMN version SET NOT NULL;

CREATE TABLE IF NOT EXISTS retenciones (
    token UUID PRIMARY KEY,
    asiento_id INTEGER NOT NULL REFERENCES asientos(id),
    estado VARCHAR(20) NOT NULL CHECK (estado IN ('ACTIVA', 'CONFIRMADA', 'LIBERADA', 'EXPIRADA')),
    expira_en TIMESTAMP NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_asientos_expiracion ON asientos(bloqueado_hasta)
    WHERE estado = 'BLOQUEADO';
COMMIT;

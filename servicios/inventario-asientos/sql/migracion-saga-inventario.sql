-- Aplicación MANUAL después de migracion-inbox-reservas.sql. No elimina ni actualiza filas.
-- Los rechazos pueden mencionar un token sin retención: token deja de ser FK en outbox.
BEGIN;
ALTER TABLE inventario_outbox
    ADD COLUMN IF NOT EXISTS clave_negocio VARCHAR(64),
    DROP CONSTRAINT IF EXISTS inventario_outbox_token_fkey,
    DROP CONSTRAINT IF EXISTS inventario_outbox_tipo_token_key,
    DROP CONSTRAINT IF EXISTS inventario_outbox_tipo_check,
    ADD CONSTRAINT inventario_outbox_tipo_check CHECK (tipo IN
        ('asiento.expirado', 'asiento.bloqueado', 'asiento.no_disponible', 'asiento.confirmado', 'asiento.confirmacion_rechazada'));
CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_retencion_tipo ON inventario_outbox(tipo, token)
    WHERE tipo IN ('asiento.expirado', 'asiento.bloqueado', 'asiento.confirmado');
CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_resultado_negocio ON inventario_outbox(tipo, clave_negocio)
    WHERE clave_negocio IS NOT NULL;
ALTER TABLE inventario_outbox
    DROP CONSTRAINT IF EXISTS inventario_outbox_rechazo_clave_check,
    ADD CONSTRAINT inventario_outbox_rechazo_clave_check CHECK
        (tipo NOT IN ('asiento.no_disponible', 'asiento.confirmacion_rechazada') OR clave_negocio IS NOT NULL);
ALTER TABLE inventario_inbox
    ALTER COLUMN tipo TYPE VARCHAR(50),
    DROP CONSTRAINT IF EXISTS inventario_inbox_tipo_check,
    ADD CONSTRAINT inventario_inbox_tipo_check CHECK
        (tipo IN ('reserva.creada', 'reserva.cancelada', 'asiento.confirmacion_solicitada'));
COMMIT;

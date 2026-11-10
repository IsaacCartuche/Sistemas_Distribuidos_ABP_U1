-- Aplicar manualmente DESPUÉS de migracion-outbox.sql. No modifica ni elimina filas.
-- Amplía exclusivamente los estados permitidos; conserva PK, FK, índice y demás CHECKs.
BEGIN;
ALTER TABLE inventario_outbox
    DROP CONSTRAINT IF EXISTS inventario_outbox_estado_check,
    ADD CONSTRAINT inventario_outbox_estado_check
        CHECK (estado IN ('ESPERA_CONTEXTO', 'PENDIENTE', 'ENVIADO', 'ERROR_DATOS'));
COMMIT;

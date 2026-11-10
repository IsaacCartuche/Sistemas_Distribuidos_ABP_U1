-- Aditiva. Aplicación manual sobre db_inventario; NO ejecutada por Spring.
-- Requiere el esquema de retenciones ya instalado. No rellena eventos históricos.
BEGIN;
CREATE TABLE IF NOT EXISTS inventario_outbox (
    event_id UUID PRIMARY KEY,
    token UUID NOT NULL REFERENCES retenciones(token),
    tipo VARCHAR(50) NOT NULL CHECK (tipo = 'asiento.expirado'),
    mensaje TEXT NOT NULL,
    correlation_id UUID,
    estado VARCHAR(30) NOT NULL DEFAULT 'ESPERA_CONTEXTO'
        CHECK (estado IN ('ESPERA_CONTEXTO', 'PENDIENTE', 'ENVIADO')),
    intentos INTEGER NOT NULL DEFAULT 0 CHECK (intentos >= 0),
    creado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    siguiente_intento TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    enviado_en TIMESTAMPTZ,
    ultimo_error VARCHAR(100),
    UNIQUE(tipo, token),
    CHECK (estado = 'ESPERA_CONTEXTO' OR correlation_id IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS idx_outbox_pendientes ON inventario_outbox(siguiente_intento, creado_en)
    WHERE estado = 'PENDIENTE';
COMMIT;

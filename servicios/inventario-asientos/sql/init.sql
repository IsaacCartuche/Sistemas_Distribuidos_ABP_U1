-- Base de datos: db_inventario
-- Servicio: Inventario de Asientos

CREATE TABLE IF NOT EXISTS asientos (
    id SERIAL PRIMARY KEY,
    vuelo_id INTEGER NOT NULL,
    numero_asiento VARCHAR(10) NOT NULL,
    estado VARCHAR(20) NOT NULL DEFAULT 'DISPONIBLE'
        CHECK (estado IN ('DISPONIBLE', 'BLOQUEADO', 'RESERVADO')),
    bloqueado_hasta TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    bloqueo_token UUID,
    UNIQUE(vuelo_id, numero_asiento)
);

CREATE INDEX IF NOT EXISTS idx_asientos_vuelo_estado ON asientos(vuelo_id, estado);

-- Historial necesario para no reactivar tokens antiguos después de liberar/rebloquear.
CREATE TABLE IF NOT EXISTS retenciones (
    token UUID PRIMARY KEY,
    asiento_id INTEGER NOT NULL REFERENCES asientos(id),
    estado VARCHAR(20) NOT NULL CHECK (estado IN ('ACTIVA', 'CONFIRMADA', 'LIBERADA', 'EXPIRADA')),
    expira_en TIMESTAMP NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_asientos_expiracion ON asientos(bloqueado_hasta)
    WHERE estado = 'BLOQUEADO';

CREATE TABLE IF NOT EXISTS inventario_outbox (
    event_id UUID PRIMARY KEY,
    token UUID NOT NULL REFERENCES retenciones(token),
    tipo VARCHAR(50) NOT NULL CHECK (tipo = 'asiento.expirado'),
    mensaje TEXT NOT NULL,
    correlation_id UUID,
    estado VARCHAR(30) NOT NULL DEFAULT 'ESPERA_CONTEXTO'
        CHECK (estado IN ('ESPERA_CONTEXTO', 'PENDIENTE', 'ENVIADO', 'ERROR_DATOS')),
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

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

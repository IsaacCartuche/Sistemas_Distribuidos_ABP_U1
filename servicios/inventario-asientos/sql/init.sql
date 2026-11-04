-- Base de datos: db_inventario
-- Servicio: Inventario de Asientos

CREATE TABLE IF NOT EXISTS asientos (
    id SERIAL PRIMARY KEY,
    vuelo_id INTEGER NOT NULL,
    numero_asiento VARCHAR(10) NOT NULL,
    estado VARCHAR(20) DEFAULT 'DISPONIBLE',
    bloqueado_hasta TIMESTAMP,
    version INTEGER DEFAULT 0,
    UNIQUE(vuelo_id, numero_asiento)
);

CREATE INDEX IF NOT EXISTS idx_asientos_vuelo_estado ON asientos(vuelo_id, estado);

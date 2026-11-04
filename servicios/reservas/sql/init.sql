-- Base de datos: db_reservas
-- Servicio: Reservas

CREATE TABLE IF NOT EXISTS reservas (
    id SERIAL PRIMARY KEY,
    vuelo_id INTEGER NOT NULL,
    usuario_id INTEGER NOT NULL,
    asiento VARCHAR(10) NOT NULL,
    estado VARCHAR(20) DEFAULT 'PENDIENTE',
    fecha_creacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    fecha_expiracion TIMESTAMP,
    total DECIMAL(10,2) NOT NULL,
    idempotency_key VARCHAR(100) UNIQUE,
    version INTEGER DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_reservas_estado ON reservas(estado);
CREATE INDEX IF NOT EXISTS idx_reservas_idempotency ON reservas(idempotency_key);

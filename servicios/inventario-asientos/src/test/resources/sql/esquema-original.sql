-- Fixture del esquema anterior, exclusivamente para PostgreSQL temporal de pruebas.
CREATE TABLE asientos (
    id SERIAL PRIMARY KEY,
    vuelo_id INTEGER NOT NULL,
    numero_asiento VARCHAR(10) NOT NULL,
    estado VARCHAR(20) DEFAULT 'DISPONIBLE',
    bloqueado_hasta TIMESTAMP,
    version INTEGER DEFAULT 0,
    UNIQUE(vuelo_id, numero_asiento)
);
CREATE INDEX idx_asientos_vuelo_estado ON asientos(vuelo_id, estado);
INSERT INTO asientos(vuelo_id, numero_asiento, estado, version)
VALUES (1, '1A', 'DISPONIBLE', 0), (1, '1B', 'RESERVADO', 3);

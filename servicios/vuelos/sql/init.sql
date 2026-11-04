-- Base de datos: db_vuelos
-- Servicio: Vuelos

CREATE TABLE IF NOT EXISTS vuelos (
    id SERIAL PRIMARY KEY,
    origen VARCHAR(100) NOT NULL,
    destino VARCHAR(100) NOT NULL,
    fecha_salida DATE NOT NULL,
    hora_salida TIME NOT NULL,
    precio DECIMAL(10,2) NOT NULL,
    estado VARCHAR(20) DEFAULT 'ACTIVO',
    capacidad_total INTEGER NOT NULL,
    capacidad_disponible INTEGER NOT NULL,
    fecha_creacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_vuelos_origen_destino_fecha ON vuelos(origen, destino, fecha_salida);

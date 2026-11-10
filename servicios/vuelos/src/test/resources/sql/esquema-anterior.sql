-- Fixture del esquema anterior, exclusivamente en PostgreSQL temporal.
CREATE TABLE vuelos (
    id SERIAL PRIMARY KEY, origen VARCHAR(100) NOT NULL, destino VARCHAR(100) NOT NULL,
    fecha_salida DATE NOT NULL, hora_salida TIME NOT NULL, precio DECIMAL(10,2) NOT NULL,
    estado VARCHAR(20) DEFAULT 'ACTIVO', capacidad_total INTEGER NOT NULL,
    capacidad_disponible INTEGER NOT NULL, fecha_creacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_vuelos_origen_destino_fecha ON vuelos(origen, destino, fecha_salida);
INSERT INTO vuelos(origen, destino, fecha_salida, hora_salida, precio, estado, capacidad_total, capacidad_disponible)
VALUES ('Quito', 'Loja', '2027-05-10', '09:30:00', 125.50, 'ACTIVO', 100, 80);

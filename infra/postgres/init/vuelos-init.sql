-- Vuelos Service - Datos de prueba
-- Base de datos: db_vuelos

CREATE TABLE IF NOT EXISTS vuelos (
    id BIGSERIAL PRIMARY KEY,
    origen VARCHAR(100) NOT NULL,
    destino VARCHAR(100) NOT NULL,
    fecha_salida DATE NOT NULL,
    hora_salida TIME NOT NULL,
    precio DECIMAL(10,2) NOT NULL,
    estado VARCHAR(20) NOT NULL DEFAULT 'PROGRAMADO',
    capacidad_total INTEGER NOT NULL,
    capacidad_disponible INTEGER NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Insertar vuelos de prueba
INSERT INTO vuelos (origen, destino, fecha_salida, hora_salida, precio, estado, capacidad_total, capacidad_disponible) VALUES
('Madrid', 'Barcelona', '2026-11-15', '08:00:00', 89.99, 'PROGRAMADO', 150, 150),
('Madrid', 'Barcelona', '2026-11-15', '12:00:00', 99.99, 'PROGRAMADO', 180, 180),
('Madrid', 'Barcelona', '2026-11-15', '18:00:00', 109.99, 'PROGRAMADO', 150, 150),
('Barcelona', 'Madrid', '2026-11-15', '07:30:00', 89.99, 'PROGRAMADO', 150, 150),
('Barcelona', 'Madrid', '2026-11-15', '14:00:00', 99.99, 'PROGRAMADO', 180, 180),
('Barcelona', 'Madrid', '2026-11-15', '20:00:00', 119.99, 'PROGRAMADO', 150, 150),
('Madrid', 'Sevilla', '2026-11-16', '09:00:00', 79.99, 'PROGRAMADO', 120, 120),
('Sevilla', 'Madrid', '2026-11-16', '16:00:00', 79.99, 'PROGRAMADO', 120, 120),
('Madrid', 'Valencia', '2026-11-17', '10:00:00', 69.99, 'PROGRAMADO', 100, 100),
('Valencia', 'Madrid', '2026-11-17', '18:30:00', 69.99, 'PROGRAMADO', 100, 100);
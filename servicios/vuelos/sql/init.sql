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
    fecha_creacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0)
);

CREATE INDEX IF NOT EXISTS idx_vuelos_origen_destino_fecha ON vuelos(origen, destino, fecha_salida);

-- Datos de prueba
INSERT INTO vuelos (origen, destino, fecha_salida, hora_salida, precio, estado, capacidad_total, capacidad_disponible) VALUES
('Madrid', 'Barcelona', '2026-11-15', '08:00:00', 89.99, 'ACTIVO', 150, 150),
('Madrid', 'Barcelona', '2026-11-15', '12:00:00', 99.99, 'ACTIVO', 180, 180),
('Madrid', 'Barcelona', '2026-11-15', '18:00:00', 109.99, 'ACTIVO', 150, 150),
('Barcelona', 'Madrid', '2026-11-15', '07:30:00', 89.99, 'ACTIVO', 150, 150),
('Barcelona', 'Madrid', '2026-11-15', '14:00:00', 99.99, 'ACTIVO', 180, 180),
('Barcelona', 'Madrid', '2026-11-15', '20:00:00', 119.99, 'ACTIVO', 150, 150),
('Madrid', 'Sevilla', '2026-11-16', '09:00:00', 79.99, 'ACTIVO', 120, 120),
('Sevilla', 'Madrid', '2026-11-16', '16:00:00', 79.99, 'ACTIVO', 120, 120),
('Madrid', 'Valencia', '2026-11-17', '10:00:00', 69.99, 'ACTIVO', 100, 100),
('Valencia', 'Madrid', '2026-11-17', '18:30:00', 69.99, 'ACTIVO', 100, 100);

CREATE TABLE IF NOT EXISTS vuelos_outbox (
    event_id UUID PRIMARY KEY,
    correlation_id UUID NOT NULL,
    vuelo_id INTEGER NOT NULL REFERENCES vuelos(id),
    revision INTEGER NOT NULL CHECK (revision > 0),
    tipo VARCHAR(50) NOT NULL CHECK (tipo = 'vuelos.actualizados'),
    mensaje TEXT NOT NULL,
    estado VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE' CHECK (estado IN ('PENDIENTE', 'ENVIADO', 'ERROR_DATOS')),
    intentos INTEGER NOT NULL DEFAULT 0 CHECK (intentos >= 0),
    creado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    siguiente_intento TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    enviado_en TIMESTAMPTZ,
    ultimo_error VARCHAR(100),
    UNIQUE(vuelo_id, revision)
);
CREATE INDEX IF NOT EXISTS idx_vuelos_outbox_pendientes ON vuelos_outbox(siguiente_intento, creado_en)
    WHERE estado = 'PENDIENTE';

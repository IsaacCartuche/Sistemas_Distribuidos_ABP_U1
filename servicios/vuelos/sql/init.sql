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

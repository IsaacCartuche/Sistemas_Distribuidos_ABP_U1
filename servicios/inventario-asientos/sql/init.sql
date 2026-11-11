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

-- Datos de prueba: Asientos para vuelos
-- Vuelo 1: Madrid -> Barcelona (capacidad 150, pero solo agregamos algunos de prueba)
INSERT INTO asientos (vuelo_id, numero_asiento, estado) VALUES
(1, '1A', 'DISPONIBLE'), (1, '1B', 'DISPONIBLE'), (1, '1C', 'DISPONIBLE'),
(1, '2A', 'DISPONIBLE'), (1, '2B', 'DISPONIBLE'), (1, '2C', 'DISPONIBLE'),
(1, '3A', 'DISPONIBLE'), (1, '3B', 'DISPONIBLE'), (1, '3C', 'DISPONIBLE'),
(1, '4A', 'DISPONIBLE'), (1, '4B', 'DISPONIBLE'), (1, '4C', 'DISPONIBLE'),
(1, '5A', 'DISPONIBLE'), (1, '5B', 'DISPONIBLE'), (1, '5C', 'DISPONIBLE');

-- Vuelo 2: Madrid -> Barcelona (capacidad 180)
INSERT INTO asientos (vuelo_id, numero_asiento, estado) VALUES
(2, '1A', 'DISPONIBLE'), (2, '1B', 'DISPONIBLE'), (2, '1C', 'DISPONIBLE'),
(2, '2A', 'DISPONIBLE'), (2, '2B', 'DISPONIBLE'), (2, '2C', 'DISPONIBLE');

-- Vuelo 3: Madrid -> Barcelona (capacidad 150)
INSERT INTO asientos (vuelo_id, numero_asiento, estado) VALUES
(3, '1A', 'DISPONIBLE'), (3, '1B', 'DISPONIBLE'), (3, '1C', 'DISPONIBLE');

-- Vuelo 4: Barcelona -> Madrid (capacidad 150)
INSERT INTO asientos (vuelo_id, numero_asiento, estado) VALUES
(4, '1A', 'DISPONIBLE'), (4, '1B', 'DISPONIBLE'), (4, '1C', 'DISPONIBLE');

-- Vuelo 5: Barcelona -> Madrid (capacidad 180)
INSERT INTO asientos (vuelo_id, numero_asiento, estado) VALUES
(5, '1A', 'DISPONIBLE'), (5, '1B', 'DISPONIBLE'), (5, '1C', 'DISPONIBLE');

-- Vuelo 6: Barcelona -> Madrid (capacidad 150)
INSERT INTO asientos (vuelo_id, numero_asiento, estado) VALUES
(6, '1A', 'DISPONIBLE'), (6, '1B', 'DISPONIBLE'), (6, '1C', 'DISPONIBLE');

-- Vuelo 7: Madrid -> Sevilla (capacidad 120)
INSERT INTO asientos (vuelo_id, numero_asiento, estado) VALUES
(7, '1A', 'DISPONIBLE'), (7, '1B', 'DISPONIBLE'), (7, '1C', 'DISPONIBLE');

-- Vuelo 8: Sevilla -> Madrid (capacidad 120)
INSERT INTO asientos (vuelo_id, numero_asiento, estado) VALUES
(8, '1A', 'DISPONIBLE'), (8, '1B', 'DISPONIBLE'), (8, '1C', 'DISPONIBLE');

-- Vuelo 9: Madrid -> Valencia (capacidad 100)
INSERT INTO asientos (vuelo_id, numero_asiento, estado) VALUES
(9, '1A', 'DISPONIBLE'), (9, '1B', 'DISPONIBLE'), (9, '1C', 'DISPONIBLE');

-- Vuelo 10: Valencia -> Madrid (capacidad 100)
INSERT INTO asientos (vuelo_id, numero_asiento, estado) VALUES
(10, '1A', 'DISPONIBLE'), (10, '1B', 'DISPONIBLE'), (10, '1C', 'DISPONIBLE');

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
    token UUID NOT NULL,
    tipo VARCHAR(50) NOT NULL CHECK (tipo IN ('asiento.expirado', 'asiento.bloqueado', 'asiento.no_disponible',
        'asiento.confirmado', 'asiento.confirmacion_rechazada')),
    mensaje TEXT NOT NULL,
    correlation_id UUID,
    estado VARCHAR(30) NOT NULL DEFAULT 'ESPERA_CONTEXTO'
        CHECK (estado IN ('ESPERA_CONTEXTO', 'PENDIENTE', 'ENVIADO', 'ERROR_DATOS')),
    intentos INTEGER NOT NULL DEFAULT 0 CHECK (intentos >= 0),
    creado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    siguiente_intento TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    enviado_en TIMESTAMPTZ,
    ultimo_error VARCHAR(100),
    clave_negocio VARCHAR(64),
    CONSTRAINT inventario_outbox_rechazo_clave_check CHECK
        (tipo NOT IN ('asiento.no_disponible', 'asiento.confirmacion_rechazada') OR clave_negocio IS NOT NULL),
    CHECK (estado = 'ESPERA_CONTEXTO' OR correlation_id IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS idx_outbox_pendientes ON inventario_outbox(siguiente_intento, creado_en)
    WHERE estado = 'PENDIENTE';
CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_retencion_tipo ON inventario_outbox(tipo, token)
    WHERE tipo IN ('asiento.expirado', 'asiento.bloqueado', 'asiento.confirmado');
CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_resultado_negocio ON inventario_outbox(tipo, clave_negocio)
    WHERE clave_negocio IS NOT NULL;

CREATE TABLE IF NOT EXISTS inventario_inbox (
    consumidor VARCHAR(80) NOT NULL,
    event_id UUID NOT NULL,
    tipo VARCHAR(50) NOT NULL CHECK (tipo IN ('reserva.creada', 'reserva.cancelada', 'asiento.confirmacion_solicitada')),
    huella CHAR(64) NOT NULL,
    resultado VARCHAR(80),
    recibido_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    procesado_en TIMESTAMPTZ,
    PRIMARY KEY(consumidor, event_id)
);
CREATE TABLE IF NOT EXISTS inventario_solicitudes (
    reserva_clave TEXT PRIMARY KEY,
    correlation_id UUID NOT NULL,
    token UUID NOT NULL UNIQUE,
    vuelo_id INTEGER NOT NULL CHECK (vuelo_id > 0),
    numero_asiento VARCHAR(10) NOT NULL,
    asiento_id INTEGER REFERENCES asientos(id),
    estado VARCHAR(20) NOT NULL CHECK (estado IN ('RECIBIDA', 'BLOQUEADA', 'CANCELADA', 'RECHAZADA')),
    creado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

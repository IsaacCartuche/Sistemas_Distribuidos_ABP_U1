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

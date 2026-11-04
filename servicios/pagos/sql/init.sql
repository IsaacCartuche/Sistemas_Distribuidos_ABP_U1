-- Base de datos: db_pagos
-- Servicio: Pagos

CREATE TABLE IF NOT EXISTS pagos (
    id SERIAL PRIMARY KEY,
    reserva_id INTEGER NOT NULL,
    monto DECIMAL(10,2) NOT NULL,
    estado VARCHAR(20) DEFAULT 'PENDIENTE',
    metodo_pago VARCHAR(50),
    transaccion_externa VARCHAR(100),
    fecha_procesamiento TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    idempotency_key VARCHAR(100) UNIQUE,
    version INTEGER DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_pagos_estado ON pagos(estado);
CREATE INDEX IF NOT EXISTS idx_pagos_reserva ON pagos(reserva_id);

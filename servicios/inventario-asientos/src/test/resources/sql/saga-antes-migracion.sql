-- Fixtures anteriores a la ampliación Saga, SOLO en la base temporal de migraciones.
INSERT INTO inventario_inbox(consumidor, event_id, tipo, huella, resultado, recibido_en, procesado_en)
VALUES ('inventario.reservas.v1', '40000000-0000-0000-0000-000000000001', 'reserva.creada',
        repeat('a', 64), 'ASIENTO_BLOQUEADO', '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z');
INSERT INTO inventario_solicitudes(reserva_clave, correlation_id, token, vuelo_id, numero_asiento, asiento_id, estado)
VALUES ('123', '30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
        1, '1A', 1, 'BLOQUEADA');

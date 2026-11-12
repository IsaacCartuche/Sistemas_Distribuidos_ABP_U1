-- Fixtures SOLO en la base temporal de migraciones; simulan filas de la versión anterior.
INSERT INTO retenciones(token, asiento_id, estado, expira_en) VALUES
('10000000-0000-0000-0000-000000000001', 1, 'EXPIRADA', '2026-01-01'),
('10000000-0000-0000-0000-000000000002', 1, 'EXPIRADA', '2026-01-01'),
('10000000-0000-0000-0000-000000000003', 1, 'EXPIRADA', '2026-01-01');
INSERT INTO inventario_outbox(event_id, token, tipo, mensaje, correlation_id, estado) VALUES
('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
 'asiento.expirado', '{"fixture":"conservar"}', NULL, 'ESPERA_CONTEXTO'),
('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002',
 'asiento.expirado', '{"fixture":"conservar"}', '30000000-0000-0000-0000-000000000001', 'PENDIENTE'),
('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000003',
 'asiento.expirado', '{"fixture":"conservar"}', '30000000-0000-0000-0000-000000000002', 'ENVIADO');

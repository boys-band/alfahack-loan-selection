-- Synthetic fixtures from contracts/examples/seed.json. Idempotent inserts.
BEGIN;
INSERT INTO source_fixtures (company_id, source, payload) VALUES ('00000000-0000-4000-8000-000000000101', 'REGISTRY', '{"registryStatus": "ACTIVE", "registryRegisteredAt": "2020-01-15", "needsDescription": false, "disputedFacts": false}'::jsonb) ON CONFLICT DO NOTHING;
INSERT INTO source_fixtures (company_id, source, payload) VALUES ('00000000-0000-4000-8000-000000000101', 'BKI', '{"creditHistoryScore": 800, "hasCurrentOverdue": false}'::jsonb) ON CONFLICT DO NOTHING;
INSERT INTO source_fixtures (company_id, source, payload) VALUES ('00000000-0000-4000-8000-000000000101', 'FNS', '{"taxDebtRub": 0}'::jsonb) ON CONFLICT DO NOTHING;
INSERT INTO source_fixtures (company_id, source, payload) VALUES ('00000000-0000-4000-8000-000000000102', 'REGISTRY', '{"registryStatus": "ACTIVE", "registryRegisteredAt": "2020-01-15", "needsDescription": false, "disputedFacts": false}'::jsonb) ON CONFLICT DO NOTHING;
INSERT INTO source_fixtures (company_id, source, payload) VALUES ('00000000-0000-4000-8000-000000000102', 'BKI', '{"creditHistoryScore": 800, "hasCurrentOverdue": true}'::jsonb) ON CONFLICT DO NOTHING;
INSERT INTO source_fixtures (company_id, source, payload) VALUES ('00000000-0000-4000-8000-000000000102', 'FNS', '{"taxDebtRub": 0}'::jsonb) ON CONFLICT DO NOTHING;
INSERT INTO source_fixtures (company_id, source, payload) VALUES ('00000000-0000-4000-8000-000000000103', 'REGISTRY', '{"registryStatus": "ACTIVE", "registryRegisteredAt": "2020-01-15", "needsDescription": true, "disputedFacts": false}'::jsonb) ON CONFLICT DO NOTHING;
INSERT INTO source_fixtures (company_id, source, payload) VALUES ('00000000-0000-4000-8000-000000000103', 'BKI', '{"creditHistoryScore": 800, "hasCurrentOverdue": false}'::jsonb) ON CONFLICT DO NOTHING;
INSERT INTO source_fixtures (company_id, source, payload) VALUES ('00000000-0000-4000-8000-000000000103', 'FNS', '{"taxDebtRub": 0}'::jsonb) ON CONFLICT DO NOTHING;
INSERT INTO source_fixtures (company_id, source, payload) VALUES ('00000000-0000-4000-8000-000000000104', 'REGISTRY', '{"registryStatus": "ACTIVE", "registryRegisteredAt": "2020-01-15", "needsDescription": false, "disputedFacts": true}'::jsonb) ON CONFLICT DO NOTHING;
INSERT INTO source_fixtures (company_id, source, payload) VALUES ('00000000-0000-4000-8000-000000000104', 'BKI', '{"creditHistoryScore": 800, "hasCurrentOverdue": false}'::jsonb) ON CONFLICT DO NOTHING;
INSERT INTO source_fixtures (company_id, source, payload) VALUES ('00000000-0000-4000-8000-000000000104', 'FNS', '{"taxDebtRub": 0}'::jsonb) ON CONFLICT DO NOTHING;
INSERT INTO source_fixtures (company_id, source, payload) VALUES ('00000000-0000-4000-8000-000000000105', 'REGISTRY', '{"registryStatus": "ACTIVE", "registryRegisteredAt": "2026-01-15", "needsDescription": false, "disputedFacts": false}'::jsonb) ON CONFLICT DO NOTHING;
INSERT INTO source_fixtures (company_id, source, payload) VALUES ('00000000-0000-4000-8000-000000000105', 'BKI', '{"creditHistoryScore": 620, "hasCurrentOverdue": false}'::jsonb) ON CONFLICT DO NOTHING;
INSERT INTO source_fixtures (company_id, source, payload) VALUES ('00000000-0000-4000-8000-000000000105', 'FNS', '{"taxDebtRub": 0}'::jsonb) ON CONFLICT DO NOTHING;
COMMIT;

-- Synthetic fixtures from contracts/examples/seed.json. Idempotent inserts.
BEGIN;
INSERT INTO account_fixtures (id, company_id, label, status) VALUES ('00000000-0000-4000-8000-000000001101', '00000000-0000-4000-8000-000000000101', 'Основной демосчет', 'ACTIVE') ON CONFLICT DO NOTHING;
INSERT INTO account_fixtures (id, company_id, label, status) VALUES ('00000000-0000-4000-8000-000000001102', '00000000-0000-4000-8000-000000000102', 'Основной демосчет', 'ACTIVE') ON CONFLICT DO NOTHING;
INSERT INTO account_fixtures (id, company_id, label, status) VALUES ('00000000-0000-4000-8000-000000001103', '00000000-0000-4000-8000-000000000103', 'Основной демосчет', 'ACTIVE') ON CONFLICT DO NOTHING;
INSERT INTO account_fixtures (id, company_id, label, status) VALUES ('00000000-0000-4000-8000-000000001104', '00000000-0000-4000-8000-000000000104', 'Основной демосчет', 'ACTIVE') ON CONFLICT DO NOTHING;
INSERT INTO account_fixtures (id, company_id, label, status) VALUES ('00000000-0000-4000-8000-000000001105', '00000000-0000-4000-8000-000000000105', 'Основной демосчет', 'ACTIVE') ON CONFLICT DO NOTHING;
INSERT INTO account_fixtures (id, company_id, label, status) VALUES ('00000000-0000-4000-8000-000000001199', '00000000-0000-4000-8000-000000000101', 'Счет с ошибочными реквизитами', 'INVALID') ON CONFLICT DO NOTHING;
COMMIT;

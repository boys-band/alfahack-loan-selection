-- Synthetic fixtures from contracts/examples/seed.json. Idempotent inserts.
BEGIN;
INSERT INTO companies (id, legal_name, inn, legal_form, registered_at, settlement_account_id) VALUES ('00000000-0000-4000-8000-000000000101', 'ООО «Демо Мастерская»', '7700000002', 'LLC', '2020-01-15', '00000000-0000-4000-8000-000000001101') ON CONFLICT DO NOTHING;
INSERT INTO customer_companies (customer_id, company_id) VALUES ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000101') ON CONFLICT DO NOTHING;
INSERT INTO companies (id, legal_name, inn, legal_form, registered_at, settlement_account_id) VALUES ('00000000-0000-4000-8000-000000000102', 'ООО «Демо Просрочка»', '7700000010', 'LLC', '2020-01-15', '00000000-0000-4000-8000-000000001102') ON CONFLICT DO NOTHING;
INSERT INTO customer_companies (customer_id, company_id) VALUES ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000102') ON CONFLICT DO NOTHING;
INSERT INTO companies (id, legal_name, inn, legal_form, registered_at, settlement_account_id) VALUES ('00000000-0000-4000-8000-000000000103', 'ООО «Демо Нужны сведения»', '7700000103', 'LLC', '2020-01-15', '00000000-0000-4000-8000-000000001103') ON CONFLICT DO NOTHING;
INSERT INTO customer_companies (customer_id, company_id) VALUES ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000103') ON CONFLICT DO NOTHING;
INSERT INTO companies (id, legal_name, inn, legal_form, registered_at, settlement_account_id) VALUES ('00000000-0000-4000-8000-000000000104', 'ООО «Демо Спорные данные»', '7700000104', 'LLC', '2020-01-15', '00000000-0000-4000-8000-000000001104') ON CONFLICT DO NOTHING;
INSERT INTO customer_companies (customer_id, company_id) VALUES ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000104') ON CONFLICT DO NOTHING;
INSERT INTO companies (id, legal_name, inn, legal_form, registered_at, settlement_account_id) VALUES ('00000000-0000-4000-8000-000000000105', 'ООО «Демо Ручной анализ»', '7700000105', 'LLC', '2026-01-15', '00000000-0000-4000-8000-000000001105') ON CONFLICT DO NOTHING;
INSERT INTO customer_companies (customer_id, company_id) VALUES ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000105') ON CONFLICT DO NOTHING;
COMMIT;

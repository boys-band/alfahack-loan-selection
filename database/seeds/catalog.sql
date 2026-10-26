-- Synthetic fixtures from contracts/examples/seed.json. Idempotent inserts.
BEGIN;
INSERT INTO product_versions (product_id, version, name, description, purpose_codes, min_amount_rub, max_amount_rub, min_term_months, max_term_months, annual_rate_bps, min_business_age_months, max_debt_service_ratio_bps) VALUES ('working-capital', '1', 'На развитие бизнеса', 'Синтетический кредит для демонстрации конвейера', ARRAY['WORKING_CAPITAL','EQUIPMENT','REFINANCING']::text[], 100000, 5000000, 6, 36, 2400, 6, 5000) ON CONFLICT DO NOTHING;
INSERT INTO current_products (product_id, version) VALUES ('working-capital', '1') ON CONFLICT DO NOTHING;
INSERT INTO product_versions (product_id, version, name, description, purpose_codes, min_amount_rub, max_amount_rub, min_term_months, max_term_months, annual_rate_bps, min_business_age_months, max_debt_service_ratio_bps) VALUES ('equipment-finance', '1', 'На оборудование', 'Синтетический кредит на оборудование', ARRAY['EQUIPMENT']::text[], 100000, 10000000, 12, 60, 2200, 12, 5000) ON CONFLICT DO NOTHING;
INSERT INTO current_products (product_id, version) VALUES ('equipment-finance', '1') ON CONFLICT DO NOTHING;
COMMIT;

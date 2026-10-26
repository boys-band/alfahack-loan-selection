SET ROLE disbursement_owner;
CREATE TABLE schema_migrations (version text PRIMARY KEY, checksum varchar(64) NOT NULL, applied_at timestamptz NOT NULL DEFAULT now(), seeded_at timestamptz);
INSERT INTO schema_migrations(version, checksum, seeded_at) VALUES ('001_initial', repeat('0',64), now());
RESET ROLE;
GRANT USAGE ON SCHEMA public TO disbursement_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO disbursement_app;
REVOKE ALL ON schema_migrations FROM disbursement_app;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO disbursement_app;

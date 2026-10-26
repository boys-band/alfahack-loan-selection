-- Run with an administrator connection to alfa_decision after the common DB bootstrap.
-- The accepted 001_initial.sql and common schema_migrations permissions are unchanged.
\set ON_ERROR_STOP on
SET ROLE decision_owner;
\ir ../migrations/002_response_documents.sql
RESET ROLE;
GRANT SELECT,INSERT,UPDATE,DELETE ON decision_documents,action_documents TO decision_app;
INSERT INTO schema_migrations(version,checksum,seeded_at)
VALUES ('002_response_documents', :'migration_checksum', clock_timestamp());

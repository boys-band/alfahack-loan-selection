CREATE ROLE disbursement_owner NOLOGIN;
CREATE ROLE disbursement_app LOGIN PASSWORD 'test-app-only' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE DATABASE alfa_disbursement OWNER disbursement_owner;
REVOKE CONNECT, TEMPORARY ON DATABASE alfa_disbursement FROM PUBLIC;
GRANT CONNECT ON DATABASE alfa_disbursement TO disbursement_app;
ALTER DATABASE alfa_disbursement SET timezone = 'UTC';
\connect alfa_disbursement
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SET ROLE disbursement_owner;

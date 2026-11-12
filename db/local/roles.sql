-- Local and test databases only: the login the app uses. Not a migration.
-- In AWS the equivalent login is created by Terraform (phase 3) and granted ledger_app.
-- V003 creates ledger_app if missing and grants it table privileges.
CREATE ROLE ledger_app NOLOGIN;
CREATE ROLE minibank_app LOGIN PASSWORD 'minibank_app' IN ROLE ledger_app;

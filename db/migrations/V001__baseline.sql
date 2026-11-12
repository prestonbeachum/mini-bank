-- V001: baseline so Flyway owns the schema from day one.
-- The ledger schema (build doc section 4) arrives in phase 1 as V002+.
-- Rules: forward-only, never edit an applied migration, expand then contract.
SELECT 1;

-- Baseline migration for accounting-backend
-- Business tables will be added in future steps via incremental Flyway migrations.
-- This file establishes Flyway history and validates the migration chain.
-- No schema changes in foundation step (keeps ddl-auto=validate compatible).

SELECT 1;

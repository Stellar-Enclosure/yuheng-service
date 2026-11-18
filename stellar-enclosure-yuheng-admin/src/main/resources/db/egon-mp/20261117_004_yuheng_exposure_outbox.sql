-- The host owns the schema boundary; the Components Outbox manifest owns all objects inside it.
DO $egon$
BEGIN
    IF current_setting('egon_migration.role') <> 'SHARD' OR current_schema() <> 'public' THEN
        RAISE EXCEPTION 'YUHENG_EXPOSURE_OUTBOX_PRIMARY_SCHEMA_REQUIRED';
    END IF;
END
$egon$;

CREATE SCHEMA IF NOT EXISTS egon_outbox;

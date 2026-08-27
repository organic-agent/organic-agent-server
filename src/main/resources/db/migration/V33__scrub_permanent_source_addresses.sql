-- WES-258: IP addresses are not required for immutable business accountability and must not remain
-- indefinitely in legacy authentication, impersonation, or unified audit rows.
DROP TRIGGER trg_admin_audit_logs_immutable ON admin_audit_logs;

UPDATE admin_audit_logs
SET source_address = NULL
WHERE source_address IS NOT NULL;

CREATE TRIGGER trg_admin_audit_logs_immutable
    BEFORE UPDATE OR DELETE ON admin_audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION prevent_admin_audit_log_mutation();

UPDATE admin_auth_events
SET source_address = NULL
WHERE source_address IS NOT NULL;

UPDATE admin_impersonation_sessions
SET source_address = NULL
WHERE source_address IS NOT NULL;

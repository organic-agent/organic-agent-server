-- WES-258: 영구 감사에는 ID/상태/버전/작업 메타데이터만 남긴다. 이름·제목·본문·PII
-- 원문은 복원 기한이 남은 revision의 대상별 allowlist payload에만 보존한 뒤 비가역 덮어쓴다.

CREATE OR REPLACE FUNCTION wes_try_audit_json(p_value TEXT)
RETURNS JSONB
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    IF p_value IS NULL THEN
        RETURN NULL;
    END IF;
    RETURN p_value::JSONB;
EXCEPTION WHEN OTHERS THEN
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION wes_safe_audit_bigint(p_value TEXT)
RETURNS BIGINT
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    IF p_value IS NULL OR p_value !~ '^[0-9]+$' THEN
        RETURN NULL;
    END IF;
    RETURN p_value::BIGINT;
EXCEPTION WHEN numeric_value_out_of_range THEN
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION wes_redact_permanent_snapshot(p_value JSONB, p_key TEXT DEFAULT '')
RETURNS JSONB
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    v_key TEXT := lower(regexp_replace(COALESCE(p_key, ''), '[^a-z0-9]', '', 'g'));
    v_kind TEXT;
    v_text TEXT;
    v_identifier_key BOOLEAN;
    v_enum_key BOOLEAN;
    v_time_key BOOLEAN;
    v_numeric_key BOOLEAN;
    v_result JSONB;
BEGIN
    IF p_value IS NULL THEN
        RETURN NULL;
    END IF;

    IF v_key LIKE '%password%' OR v_key LIKE '%secret%' OR v_key LIKE '%token%' OR
       v_key LIKE '%credential%' OR v_key LIKE '%authorization%' OR v_key LIKE '%cookie%' OR
       v_key LIKE '%oauth%' OR v_key LIKE '%providerid%' OR v_key LIKE '%storagekey%' OR
       v_key LIKE '%previewkey%' OR v_key LIKE '%annotationkey%' OR v_key LIKE '%resultkey%' OR
       v_key LIKE '%email%' OR right(v_key, 9) = 'uploadurl' OR right(v_key, 11) = 'downloadurl' OR
       right(v_key, 4) = 'name' OR right(v_key, 8) = 'nickname' OR right(v_key, 5) = 'phone' OR
       right(v_key, 11) = 'phonenumber' OR right(v_key, 7) = 'address' OR
       right(v_key, 7) = 'content' OR right(v_key, 11) = 'requesttext' OR
       right(v_key, 12) = 'deliverynote' OR v_key LIKE '%presignedurl%' OR
       v_key IN ('structuredaimetadata', 'recipientreference', 'payload') THEN
        RETURN to_jsonb('[REDACTED]'::TEXT);
    END IF;

    v_kind := jsonb_typeof(p_value);
    IF v_kind = 'object' THEN
        SELECT COALESCE(
            jsonb_object_agg(entry.key, wes_redact_permanent_snapshot(entry.value, entry.key)),
            '{}'::JSONB
        )
        INTO v_result
        FROM jsonb_each(p_value) AS entry(key, value);
        RETURN v_result;
    ELSIF v_kind = 'array' THEN
        SELECT COALESCE(
            jsonb_agg(wes_redact_permanent_snapshot(item.value, p_key) ORDER BY item.ordinality),
            '[]'::JSONB
        )
        INTO v_result
        FROM jsonb_array_elements(p_value) WITH ORDINALITY AS item(value, ordinality);
        RETURN v_result;
    ELSIF v_kind IN ('boolean', 'null') THEN
        RETURN p_value;
    END IF;

    v_identifier_key := v_key = 'id' OR right(v_key, 2) = 'id' OR right(v_key, 3) = 'ids';
    v_enum_key := v_key = 'type' OR right(v_key, 4) = 'type' OR
        v_key = 'status' OR right(v_key, 6) = 'status' OR
        v_key = 'state' OR right(v_key, 5) = 'state' OR
        v_key = 'role' OR right(v_key, 4) = 'role' OR
        v_key = 'source' OR right(v_key, 6) = 'source' OR
        v_key = 'action' OR right(v_key, 6) = 'action' OR
        v_key IN ('operation', 'outcome', 'provider');
    v_time_key := right(v_key, 2) = 'at' OR right(v_key, 8) = 'deadline' OR
        right(v_key, 7) = 'expires' OR right(v_key, 5) = 'until';
    v_numeric_key := v_identifier_key OR (
        v_key NOT LIKE '%account%' AND v_key NOT LIKE '%amount%' AND v_key NOT LIKE '%price%' AND
        v_key NOT LIKE '%cost%' AND v_key NOT LIKE '%discount%' AND v_key NOT LIKE '%score%' AND
        v_key NOT LIKE '%rating%' AND v_key NOT LIKE '%phone%' AND v_key NOT LIKE '%number%' AND
        EXISTS (
            SELECT 1
            FROM unnest(ARRAY[
                'version', 'count', 'attempt', 'revision', 'round', 'order', 'index', 'size',
                'limit', 'ttl', 'seconds', 'minutes', 'hours', 'days'
            ]) AS part
            WHERE v_key = part OR right(v_key, length(part)) = part
        )
    );

    IF v_kind = 'number' THEN
        RETURN CASE WHEN v_numeric_key THEN p_value ELSE to_jsonb('[REDACTED]'::TEXT) END;
    ELSIF v_kind = 'string' THEN
        v_text := p_value #>> '{}';
        IF v_identifier_key AND v_text ~ '^([0-9]+|[0-9a-fA-F]{16}|[0-9a-fA-F]{8}-[0-9a-fA-F-]{27})$' THEN
            RETURN p_value;
        ELSIF v_enum_key AND v_text ~ '^[A-Z][A-Z0-9_]{0,79}$' THEN
            RETURN p_value;
        ELSIF v_time_key AND v_text ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[^[:space:]]{1,80}$' THEN
            RETURN p_value;
        END IF;
    END IF;
    RETURN to_jsonb('[REDACTED]'::TEXT);
END;
$$;

CREATE OR REPLACE FUNCTION wes_scrub_restore_secrets(p_value JSONB, p_key TEXT DEFAULT '')
RETURNS JSONB
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    v_key TEXT := lower(regexp_replace(COALESCE(p_key, ''), '[^a-z0-9]', '', 'g'));
    v_kind TEXT;
    v_text TEXT;
    v_result JSONB;
BEGIN
    IF p_value IS NULL THEN
        RETURN NULL;
    END IF;
    IF v_key LIKE '%password%' OR v_key LIKE '%secret%' OR v_key LIKE '%token%' OR
       v_key LIKE '%credential%' OR v_key LIKE '%authorization%' OR v_key LIKE '%cookie%' OR
       v_key LIKE '%oauth%' OR v_key LIKE '%storagekey%' OR v_key LIKE '%previewkey%' OR
       v_key LIKE '%annotationkey%' OR v_key LIKE '%resultkey%' THEN
        RETURN to_jsonb('[REDACTED]'::TEXT);
    END IF;

    v_kind := jsonb_typeof(p_value);
    IF v_kind = 'object' THEN
        SELECT COALESCE(
            jsonb_object_agg(entry.key, wes_scrub_restore_secrets(entry.value, entry.key)),
            '{}'::JSONB
        ) INTO v_result
        FROM jsonb_each(p_value) AS entry(key, value);
        RETURN v_result;
    ELSIF v_kind = 'array' THEN
        SELECT COALESCE(
            jsonb_agg(wes_scrub_restore_secrets(item.value, p_key) ORDER BY item.ordinality),
            '[]'::JSONB
        ) INTO v_result
        FROM jsonb_array_elements(p_value) WITH ORDINALITY AS item(value, ordinality);
        RETURN v_result;
    ELSIF v_kind = 'string' THEN
        v_text := p_value #>> '{}';
        v_text := regexp_replace(
            v_text,
            '[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}',
            '[REDACTED_TOKEN]',
            'g'
        );
        v_text := regexp_replace(
            v_text,
            '(?i)Bearer[[:space:]]+[^[:space:],;]+',
            'Bearer [REDACTED]',
            'g'
        );
        v_text := regexp_replace(
            v_text,
            '(?i)(password|token|secret|credential|authorization|cookie|api[_-]?key)[[:space:]]*[:=][[:space:]]*[^[:space:],;]+',
            E'\\1=[REDACTED]',
            'g'
        );
        RETURN to_jsonb(v_text);
    END IF;
    RETURN p_value;
END;
$$;

CREATE OR REPLACE FUNCTION wes_restore_payload(p_value JSONB, p_target_type TEXT)
RETURNS JSONB
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    v_allowed TEXT[];
    v_result JSONB;
BEGIN
    IF p_value IS NULL OR jsonb_typeof(p_value) <> 'object' THEN
        RETURN NULL;
    END IF;
    v_allowed := CASE p_target_type
        WHEN 'ADMIN_ACCOUNT' THEN ARRAY[
            'version', 'username', 'displayName', 'status', 'failedLoginAttempts', 'lockedUntil'
        ]
        WHEN 'USER' THEN ARRAY[
            'type', 'id', 'version', 'deleted', 'nickname', 'email', 'role', 'userType'
        ]
        WHEN 'STUDIO' THEN ARRAY[
            'type', 'id', 'version', 'deleted', 'userId', 'name', 'galleryUrl', 'inflowChannel'
        ]
        WHEN 'GALLERY' THEN ARRAY[
            'type', 'id', 'version', 'deleted', 'studioId', 'title', 'status', 'workflowStatus',
            'selectionDeadline', 'maxSelectablePhotoCount', 'maxRetouchRoundCount'
        ]
        WHEN 'PHOTO' THEN ARRAY[
            'type', 'id', 'version', 'deleted', 'galleryId', 'displayOrder', 'status', 'uploadUrlExpiresAt'
        ]
        WHEN 'SELECTION' THEN ARRAY[
            'type', 'id', 'version', 'deleted', 'galleryId', 'status', 'submittedAt'
        ]
        WHEN 'COLLABORATION' THEN ARRAY[
            'type', 'id', 'version', 'deleted', 'galleryId', 'name', 'revoked', 'expiresAt'
        ]
        WHEN 'ALBUM' THEN ARRAY['type', 'id', 'version', 'deleted', 'galleryId', 'name']
        WHEN 'RETOUCH_REQUEST' THEN ARRAY[
            'type', 'id', 'version', 'deleted', 'galleryId', 'status', 'requestedAt', 'completedAt'
        ]
        ELSE NULL
    END;
    IF v_allowed IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT jsonb_object_agg(entry.key, wes_scrub_restore_secrets(entry.value, entry.key))
    INTO v_result
    FROM jsonb_each(p_value) AS entry(key, value)
    WHERE entry.key = ANY(v_allowed);
    RETURN v_result;
END;
$$;

CREATE OR REPLACE FUNCTION wes_is_allowed_audit_metadata(p_value TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    v_token TEXT;
    v_key TEXT;
    v_seen_keys TEXT[] := ARRAY[]::TEXT[];
BEGIN
    IF p_value IS NULL OR p_value = '' OR length(p_value) > 500 THEN
        RETURN FALSE;
    END IF;

    FOREACH v_token IN ARRAY regexp_split_to_array(p_value, '[[:space:]]+') LOOP
        v_key := split_part(v_token, '=', 1);
        IF v_key = '' OR v_key = ANY(v_seen_keys) THEN
            RETURN FALSE;
        END IF;
        v_seen_keys := array_append(v_seen_keys, v_key);

        IF v_token ~ '^(reasonCategory)=(CUSTOMER_REQUEST|DATA_CORRECTION|INCIDENT_RECOVERY|SECURITY_RESPONSE|POLICY_ENFORCEMENT|TEST_OPERATION|OTHER|UNSPECIFIED)$' OR
           v_token ~ '^(operatorReasonProvided|metadataPresent|metadataRejected|queryFilterPresent|typeFilterPresent)=(true|false)$' OR
           v_token ~ '^category=(LOGIN_SUCCEEDED|LOGIN_FAILED|ACCOUNT_LOCKED|LOGOUT|PASSWORD_CHANGED|ACCOUNT_CREATED|ACCOUNT_SUSPENDED|ACCOUNT_ACTIVATED|ACCOUNT_UNLOCKED|TEMPORARY_PASSWORD_ISSUED|CLI_RECOVERY|REVISION_RESTORED|PHOTO_PREVIEW_VIEWED|ORIGINAL_PHOTO_VIEWED|ORIGINAL_PHOTO_DOWNLOADED|RETOUCH_ARTIFACT_VIEWED|RETOUCH_ARTIFACT_DOWNLOADED|READ_ONLY_IMPERSONATION_STARTED|READ_ONLY_IMPERSONATION_VIEWED|READ_ONLY_IMPERSONATION_ENDED|RESOURCE_VIEWED|RESOURCE_CREATED|RESOURCE_UPDATED|RESOURCE_DELETED|RESOURCE_RESTORED|RESOURCE_PURGED|RESOURCE_SUSPENDED|RESOURCE_ACTIVATED|REPROCESS_REQUESTED|REPROCESS_DISPATCHED|REPROCESS_DISPATCH_FAILED|REPROCESS_DISPATCH_UNKNOWN|MUTATION_FAILED|RESPONSE_FAILED)$' OR
           v_token ~ '^route=(RESOURCE_LIST|RESOURCE_DETAIL|RESOURCE_CONTEXT|RESOURCE_READ|ADMIN_ACCOUNT_LIST|OPERATIONS_OVERVIEW|OBSERVABILITY_LINKS|TRASH_LIST|CHILD_TRASH_LIST|SYSTEM_SETTINGS|AUTH_SESSION|IMPERSONATION_READ|ADMIN_READ|AUDIT_LOG_LIST|REVISION_LIST|AUDIT_LOG_DETAIL|AUDIT_LOG_READ|MUTATION_FAILURE)$' OR
           v_token = 'end=EXPIRED' OR
           v_token ~ '^method=(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)$' OR
           v_token ~ '^status=[1-5][0-9]{2}$' OR
           v_token ~ '^(page|size|typeFilterCount|returnedCount|totalCount)=(0|[1-9][0-9]{0,18})$' OR
           v_token ~ '^adminSessionId=[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' THEN
            CONTINUE;
        END IF;
        RETURN FALSE;
    END LOOP;
    RETURN TRUE;
END;
$$;

CREATE OR REPLACE FUNCTION wes_canonical_audit_reason(p_value TEXT)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    v_value TEXT := btrim(COALESCE(p_value, ''));
    v_category TEXT;
BEGIN
    IF v_value = '' THEN
        RETURN 'reasonCategory=UNSPECIFIED operatorReasonProvided=false';
    END IF;
    IF v_value ~ '^(reasonCategory=(CUSTOMER_REQUEST|DATA_CORRECTION|INCIDENT_RECOVERY|SECURITY_RESPONSE|POLICY_ENFORCEMENT|TEST_OPERATION|OTHER|UNSPECIFIED) operatorReasonProvided=(true|false))$' THEN
        RETURN v_value;
    END IF;
    IF wes_is_allowed_audit_metadata(v_value) THEN
        RETURN v_value;
    END IF;
    v_category := substring(v_value FROM '^[[:space:]]*\[([A-Z_]+)\]');
    IF v_category IS NULL THEN
        v_category := substring(v_value FROM '^[[:space:]]*([A-Z_]+)[[:space:]]*:');
    END IF;
    IF v_category IS NULL THEN
        v_category := substring(v_value FROM '^[[:space:]]*reasonCategory=([A-Z_]+)([[:space:]]|$)');
    END IF;
    IF v_category IS NULL OR v_category NOT IN (
        'CUSTOMER_REQUEST', 'DATA_CORRECTION', 'INCIDENT_RECOVERY', 'SECURITY_RESPONSE',
        'POLICY_ENFORCEMENT', 'TEST_OPERATION', 'OTHER'
    ) THEN
        v_category := 'UNSPECIFIED';
    END IF;
    RETURN 'reasonCategory=' || v_category || ' operatorReasonProvided=true';
END;
$$;

CREATE OR REPLACE FUNCTION wes_safe_audit_target_id(p_target_type TEXT, p_target_id TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE
        WHEN p_target_type = 'AUTHENTICATION' THEN NULL
        WHEN btrim(COALESCE(p_target_id, '')) ~ '^([0-9]+|[0-9a-fA-F-]{36}|[A-Z][A-Z0-9_.-]{0,63})$'
            THEN left(btrim(p_target_id), 128)
        ELSE NULL
    END
$$;

CREATE OR REPLACE FUNCTION wes_canonical_audit_target_label(p_target_type TEXT, p_target_id TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE
        WHEN p_target_type IS NULL THEN NULL
        WHEN p_target_type = 'AUTHENTICATION' THEN 'AUTHENTICATION'
        WHEN wes_safe_audit_target_id(p_target_type, p_target_id) IS NULL THEN left(p_target_type, 120)
        ELSE left(p_target_type || ' #' || wes_safe_audit_target_id(p_target_type, p_target_id), 120)
    END
$$;

-- 원문이 필요한 유일한 장기 예외는 아직 7일 복원 기한이 남은 allowlist payload다.
UPDATE admin_entity_revisions
SET before_restore_payload = CASE
        WHEN restore_expires_at > CURRENT_TIMESTAMP THEN
            wes_restore_payload(
                COALESCE(wes_try_audit_json(before_restore_payload), wes_try_audit_json(before_snapshot)),
                target_type
            )::TEXT
        ELSE NULL
    END,
    after_restore_payload = CASE
        WHEN restore_expires_at > CURRENT_TIMESTAMP THEN
            wes_restore_payload(
                COALESCE(wes_try_audit_json(after_restore_payload), wes_try_audit_json(after_snapshot)),
                target_type
            )::TEXT
        ELSE NULL
    END;

-- target/version/상태 구조는 남기고 자유 문자열과 민감 수치는 모두 fail-closed 마스킹한다.
UPDATE admin_entity_revisions
SET target_id = COALESCE(
        wes_safe_audit_target_id(target_type, target_id),
        left('REDACTED-' || id, 128)
    ),
    target_version = COALESCE(
        target_version,
        wes_safe_audit_bigint(wes_try_audit_json(after_snapshot) ->> 'version'),
        wes_safe_audit_bigint(wes_try_audit_json(before_snapshot) ->> 'version')
    ),
    before_snapshot = CASE WHEN before_snapshot IS NULL THEN NULL ELSE COALESCE(
        wes_redact_permanent_snapshot(wes_try_audit_json(before_snapshot))::TEXT,
        '{"legacySnapshot":"[REDACTED]"}'
    ) END,
    after_snapshot = CASE WHEN after_snapshot IS NULL THEN NULL ELSE COALESCE(
        wes_redact_permanent_snapshot(wes_try_audit_json(after_snapshot))::TEXT,
        '{"legacySnapshot":"[REDACTED]"}'
    ) END,
    snapshot_schema_version = 3;

-- V23 불변 trigger는 승인된 일회성 비가역 정정 동안만 해제한다.
DROP TRIGGER IF EXISTS trg_admin_audit_logs_immutable ON admin_audit_logs;

UPDATE admin_audit_logs
SET actor_username_snapshot = CASE
        WHEN actor_admin_id IS NULL THEN NULL ELSE left('ADMIN #' || actor_admin_id, 64)
    END,
    target_label = wes_canonical_audit_target_label(target_type, target_id),
    target_id = wes_safe_audit_target_id(target_type, target_id),
    source_address = NULL,
    reason = wes_canonical_audit_reason(reason);

CREATE TRIGGER trg_admin_audit_logs_immutable
    BEFORE UPDATE OR DELETE ON admin_audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION prevent_admin_audit_log_mutation();

UPDATE admin_auth_events
SET username_snapshot = CASE
        WHEN COALESCE(target_admin_id, actor_admin_id) IS NULL THEN NULL
        ELSE left('ADMIN #' || COALESCE(target_admin_id, actor_admin_id), 64)
    END,
    source_address = NULL,
    reason = wes_canonical_audit_reason(reason);

UPDATE admin_impersonation_sessions
SET target_label = left(target_type || ' #' || target_id, 500),
    reason = wes_canonical_audit_reason(reason),
    source_address = NULL;

DROP FUNCTION wes_canonical_audit_target_label(TEXT, TEXT);
DROP FUNCTION wes_safe_audit_target_id(TEXT, TEXT);
DROP FUNCTION wes_canonical_audit_reason(TEXT);
DROP FUNCTION wes_is_allowed_audit_metadata(TEXT);
DROP FUNCTION wes_restore_payload(JSONB, TEXT);
DROP FUNCTION wes_scrub_restore_secrets(JSONB, TEXT);
DROP FUNCTION wes_redact_permanent_snapshot(JSONB, TEXT);
DROP FUNCTION wes_safe_audit_bigint(TEXT);
DROP FUNCTION wes_try_audit_json(TEXT);

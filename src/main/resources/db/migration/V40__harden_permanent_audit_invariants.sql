-- WES-258/WES-259: V38 이전에 저장된 감사 원문을 현재 exact-key 정책으로 다시 정리하고,
-- 영구 증거는 DB에서도 되돌리거나 변조할 수 없도록 고정한다. 복원용 원문은 생성 후 7일
-- 이내의 대상별 allowlist만 남기며, 만료 연장과 payload 재주입은 영구적으로 거부한다.

LOCK TABLE admin_entity_revisions, admin_audit_logs, admin_auth_events,
    admin_impersonation_sessions, admin_trash_batches, admin_child_trash_records
    IN SHARE ROW EXCLUSIVE MODE;

-- 마이그레이션 자체를 테스트 트랜잭션에서 재실행할 수 있고, 승인된 일회성 정리가 기존
-- 불변 trigger에 막히지 않게 한다. Flyway 트랜잭션이 실패하면 DROP도 함께 rollback된다.
DROP TRIGGER IF EXISTS trg_admin_entity_revisions_immutable ON admin_entity_revisions;
DROP TRIGGER IF EXISTS trg_admin_audit_logs_immutable ON admin_audit_logs;

CREATE OR REPLACE FUNCTION wes_v40_try_json(p_value TEXT)
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

CREATE OR REPLACE FUNCTION wes_v40_safe_bigint(p_value TEXT)
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

-- 런타임 AdminAuditSnapshotCodec의 exact-key 분류와 같은 목록이다. 접미사/부분문자열로
-- 추론하지 않으므로 email 주소처럼 보이는 key나 accountId 같은 미승인 key는 물리 제거된다.
CREATE OR REPLACE FUNCTION wes_v40_audit_key_policy(p_key TEXT)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    IF p_key = ANY(ARRAY[
        'id','userId','studioId','galleryId','photoId','selectionId','collaborationId','albumId',
        'roundId','retouchPhotoId','templateId','memberId','inviteId','jobId','revisionId',
        'selectedRevisionId','selectionRevisionId','resultRevisionId','previousRevisionId',
        'replacementId','uploadId','commentId','likeId','folderId','ownerId','previousOwnerId',
        'actorAdminId','parentId','childId','trashBatchId','childTrashId','resourceId','itemId',
        'groupId','guestId','collabPhotoId','notificationOutboxId','previousJobId',
        'mockRecalculationJobId','sessionId','photoIds','processingJobIds'
    ]::TEXT[]) THEN
        RETURN 'IDENTIFIER';
    ELSIF p_key = ANY(ARRAY[
        'type','status','state','role','source','action','operation','outcome','provider','userType',
        'rootType','childType','parentType','resourceType','workflowAction','workflowStatus',
        'publicStatus','galleryStatus','selectionStatus','processingStatus','reprocessStatus',
        'jobType','jobStatus','mockRecalculationStatus','deliveryStatus','inviteStatus','artifactType',
        'notificationType','trashStatus','failureCode','capability'
    ]::TEXT[]) THEN
        RETURN 'ENUM';
    ELSIF p_key = ANY(ARRAY[
        'lockedUntil','selectionDeadline','uploadUrlExpiresAt','submittedAt','expiresAt','requestedAt',
        'completedAt','restoreUntil','createdAt','updatedAt','deletedAt','lastRunAt','suspendedAt',
        'revokedAt','startedAt','deliveredAt','selectedAt','takenAt','joinedAt','lastActivityAt',
        'customerConsentedAt','inviteExpiresAt','purgeEligibleAt'
    ]::TEXT[]) THEN
        RETURN 'TIMESTAMP';
    ELSIF p_key = ANY(ARRAY[
        'version','targetVersion','restoredSnapshotVersion','failedLoginAttempts','attemptCount',
        'displayOrder','maxSelectablePhotoCount','maxRetouchRoundCount','roundNo','itemCount',
        'templateCount','returnedCount','totalCount','restoredCount','entryCount','rootEntryCount',
        'commentCount','albumTemplateReferenceCount','templateMetadataCount','photoRevisionCount',
        'selectionRevisionCount','photoStorageMetadataCount','retouchStorageMetadataCount',
        'entityRevisionCount','purgeAttemptCount','uploadUrlTtlSeconds','terminatedCount','removedCount',
        'photoCount','fieldCount','selectedCount','remainingCount','remainingRoundCount','requestedCount',
        'targetPhotoCount','byteSize','storageBytes','width','height','sortOrder','roundVersion',
        'retouchPhotoVersion','revisionNumber','expectedVersion','restoreWindowDays','ADMIN_ACCOUNT',
        'USER','STUDIO','GALLERY','PHOTO','SELECTION','COLLABORATION','ALBUM','RETOUCH_REQUEST',
        'GALLERY_MEMBER','COLLAB_COMMENT','COLLAB_LIKE','ALBUM_TEMPLATE','RETOUCH_ITEM'
    ]::TEXT[]) THEN
        RETURN 'NUMERIC';
    ELSIF p_key = ANY(ARRAY[
        'deleted','revoked','suspended','purged','assigned','revealed','reissued','force','enabled',
        'present','submitted','analyzed','annotated','hasResult','resultReady','previewReady',
        'requiresEmbedding','restorable','oneTimeReveal'
    ]::TEXT[]) THEN
        RETURN 'BOOLEAN';
    ELSIF p_key = ANY(ARRAY[
        'resource','fields','facts','sections','workflowDetails','affectedCounts','relationshipFacts',
        'payloadSummary','notification','inputConditions','comments','photos','items','members','invites',
        'galleries','albums','selections','collaborations','folders','rounds','templates','likes','guests',
        'sessions','processingJobs','replacementUploads','revisions','retouchRounds','selectedPhotos',
        'retouchedPhotos','completedResults','pendingResults','photoItems','owner','mockGallery','delivery',
        'retouchCapabilities','albumReferences','collaborationLinks','selectionReferences',
        'retouchReferences','galleryMemberships','studioMemberships','joinedGalleries','ownedStudios',
        'notifications','aiJobs','activeSessions'
    ]::TEXT[]) THEN
        RETURN 'CONTAINER';
    ELSIF p_key = ANY(ARRAY[
        'username','displayName','nickname','email','name','title','label','content','requestText',
        'deliveryNote','originalFileName','fileName','storageKey','previewKey','annotationKey','resultKey',
        'providerId','collabToken','uploadUrl','downloadUrl','presignedUrl','galleryUrl','inflowChannel',
        'recipientReference','payload','structuredAiMetadata','address','phone','phoneNumber','reason',
        'inviteUrl','collabUrl','folderName','templateName','studioName','galleryTitle','token','embedding',
        'author','message','cameraMake','cameraModel','layout','crop','algorithm','contentType',
        'resultContentType','replacement_upload_url','structured_ai_metadata','recipient_reference'
    ]::TEXT[]) THEN
        RETURN 'REDACTED';
    END IF;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION wes_v40_is_strict_timestamp(p_value TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    v_year INTEGER;
    v_month INTEGER;
    v_day INTEGER;
    v_hour INTEGER;
    v_minute INTEGER;
    v_second INTEGER;
    v_max_day INTEGER;
    v_base TEXT;
    v_offset TEXT;
    v_offset_hour INTEGER;
    v_offset_minute INTEGER;
    v_zone_id TEXT;
BEGIN
    IF p_value IS NULL OR p_value !~
       '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})(\[[A-Za-z0-9._+/-]{1,64}\])?$' THEN
        RETURN FALSE;
    END IF;
    v_year := substring(p_value FROM 1 FOR 4)::INTEGER;
    v_month := substring(p_value FROM 6 FOR 2)::INTEGER;
    v_day := substring(p_value FROM 9 FOR 2)::INTEGER;
    v_hour := substring(p_value FROM 12 FOR 2)::INTEGER;
    v_minute := substring(p_value FROM 15 FOR 2)::INTEGER;
    v_second := substring(p_value FROM 18 FOR 2)::INTEGER;
    IF v_month NOT BETWEEN 1 AND 12 OR v_hour NOT BETWEEN 0 AND 23 OR
       v_minute NOT BETWEEN 0 AND 59 OR v_second NOT BETWEEN 0 AND 59 THEN
        RETURN FALSE;
    END IF;
    v_max_day := CASE v_month
        WHEN 2 THEN CASE
            WHEN mod(v_year, 400) = 0 OR (mod(v_year, 4) = 0 AND mod(v_year, 100) <> 0) THEN 29
            ELSE 28
        END
        WHEN 4 THEN 30
        WHEN 6 THEN 30
        WHEN 9 THEN 30
        WHEN 11 THEN 30
        ELSE 31
    END;
    IF v_day NOT BETWEEN 1 AND v_max_day THEN
        RETURN FALSE;
    END IF;
    v_zone_id := substring(p_value FROM '\[([A-Za-z0-9._+/-]{1,64})\]$');
    v_base := regexp_replace(p_value, '\[[A-Za-z0-9._+/-]{1,64}\]$', '');
    v_offset := substring(v_base FROM '(Z|[+-][0-9]{2}:[0-9]{2})$');
    IF v_offset <> 'Z' THEN
        v_offset_hour := substring(v_offset FROM 2 FOR 2)::INTEGER;
        v_offset_minute := substring(v_offset FROM 5 FOR 2)::INTEGER;
        IF v_offset_hour NOT BETWEEN 0 AND 18 OR v_offset_minute NOT BETWEEN 0 AND 59 OR
           (v_offset_hour = 18 AND v_offset_minute <> 0) THEN
            RETURN FALSE;
        END IF;
    END IF;
    IF v_zone_id IS NOT NULL AND v_zone_id NOT IN ('Z', 'UTC', 'UT', 'GMT') AND NOT EXISTS (
        SELECT 1 FROM pg_timezone_names WHERE name = v_zone_id
    ) THEN
        RETURN FALSE;
    END IF;
    RETURN TRUE;
EXCEPTION WHEN OTHERS THEN
    RETURN FALSE;
END;
$$;

CREATE OR REPLACE FUNCTION wes_v40_sanitize_permanent_snapshot(p_value JSONB, p_key TEXT DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    v_policy TEXT := CASE WHEN p_key IS NULL THEN 'ROOT' ELSE wes_v40_audit_key_policy(p_key) END;
    v_kind TEXT;
    v_text TEXT;
    v_result JSONB;
BEGIN
    IF p_value IS NULL OR v_policy IS NULL THEN
        RETURN NULL;
    END IF;
    IF v_policy = 'REDACTED' THEN
        RETURN to_jsonb('[REDACTED]'::TEXT);
    END IF;
    v_kind := jsonb_typeof(p_value);
    IF v_kind = 'null' THEN
        RETURN p_value;
    ELSIF v_kind = 'object' THEN
        IF v_policy NOT IN ('ROOT', 'CONTAINER') THEN
            RETURN to_jsonb('[REDACTED]'::TEXT);
        END IF;
        SELECT COALESCE(
            jsonb_object_agg(entry.key, wes_v40_sanitize_permanent_snapshot(entry.value, entry.key)),
            '{}'::JSONB
        )
        INTO v_result
        FROM jsonb_each(p_value) AS entry(key, value)
        WHERE wes_v40_audit_key_policy(entry.key) IS NOT NULL;
        RETURN v_result;
    ELSIF v_kind = 'array' THEN
        IF v_policy = 'ROOT' THEN
            RETURN NULL;
        END IF;
        SELECT COALESCE(
            jsonb_agg(wes_v40_sanitize_permanent_snapshot(item.value, p_key) ORDER BY item.ordinality),
            '[]'::JSONB
        )
        INTO v_result
        FROM jsonb_array_elements(p_value) WITH ORDINALITY AS item(value, ordinality);
        RETURN v_result;
    ELSIF v_kind = 'boolean' THEN
        RETURN CASE WHEN v_policy = 'BOOLEAN' THEN p_value ELSE to_jsonb('[REDACTED]'::TEXT) END;
    ELSIF v_kind = 'number' THEN
        RETURN CASE
            WHEN v_policy IN ('IDENTIFIER', 'NUMERIC') THEN p_value
            ELSE to_jsonb('[REDACTED]'::TEXT)
        END;
    ELSIF v_kind = 'string' THEN
        v_text := p_value #>> '{}';
        IF v_policy = 'IDENTIFIER' AND
           v_text ~ '^([0-9]+|[0-9a-fA-F]{16}|[0-9a-fA-F]{8}-[0-9a-fA-F-]{27})$' THEN
            RETURN p_value;
        ELSIF v_policy = 'ENUM' AND v_text ~ '^[A-Z][A-Z0-9_]{0,79}$' THEN
            RETURN p_value;
        ELSIF v_policy = 'TIMESTAMP' AND wes_v40_is_strict_timestamp(v_text) THEN
            RETURN p_value;
        END IF;
    END IF;
    RETURN to_jsonb('[REDACTED]'::TEXT);
END;
$$;

CREATE OR REPLACE FUNCTION wes_v40_sanitize_changed_fields(p_value TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT string_agg(DISTINCT token, ',' ORDER BY token)
    FROM unnest(string_to_array(p_value, ',')) AS token
    WHERE wes_v40_audit_key_policy(token) IS NOT NULL
      AND token NOT IN ('type', 'id', 'version', 'label')
$$;

CREATE OR REPLACE FUNCTION wes_v40_scrub_restore_secrets(p_value JSONB)
RETURNS JSONB
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    v_kind TEXT;
    v_text TEXT;
    v_result JSONB;
BEGIN
    IF p_value IS NULL THEN
        RETURN NULL;
    END IF;
    v_kind := jsonb_typeof(p_value);
    IF v_kind = 'object' THEN
        SELECT COALESCE(jsonb_object_agg(entry.key, wes_v40_scrub_restore_secrets(entry.value)), '{}'::JSONB)
        INTO v_result
        FROM jsonb_each(p_value) AS entry(key, value);
        RETURN v_result;
    ELSIF v_kind = 'array' THEN
        SELECT COALESCE(
            jsonb_agg(wes_v40_scrub_restore_secrets(item.value) ORDER BY item.ordinality),
            '[]'::JSONB
        )
        INTO v_result
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

CREATE OR REPLACE FUNCTION wes_v40_restore_payload(p_value JSONB, p_target_type TEXT)
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
        WHEN 'ADMIN_ACCOUNT' THEN ARRAY['version','username','displayName','status','failedLoginAttempts','lockedUntil']
        WHEN 'USER' THEN ARRAY['type','id','version','deleted','nickname','email','role','userType']
        WHEN 'STUDIO' THEN ARRAY['type','id','version','deleted','userId','name','galleryUrl','inflowChannel']
        WHEN 'GALLERY' THEN ARRAY['type','id','version','deleted','studioId','title','status','workflowStatus','selectionDeadline','maxSelectablePhotoCount','maxRetouchRoundCount']
        WHEN 'PHOTO' THEN ARRAY['type','id','version','deleted','galleryId','displayOrder','status','uploadUrlExpiresAt']
        WHEN 'SELECTION' THEN ARRAY['type','id','version','deleted','galleryId','status','submittedAt']
        WHEN 'COLLABORATION' THEN ARRAY['type','id','version','deleted','galleryId','name','revoked','expiresAt']
        WHEN 'ALBUM' THEN ARRAY['type','id','version','deleted','galleryId','name']
        WHEN 'RETOUCH_REQUEST' THEN ARRAY['type','id','version','deleted','galleryId','status','requestedAt','completedAt']
        ELSE NULL
    END;
    IF v_allowed IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT jsonb_object_agg(entry.key, wes_v40_scrub_restore_secrets(entry.value))
    INTO v_result
    FROM jsonb_each(p_value) AS entry(key, value)
    WHERE entry.key = ANY(v_allowed);
    RETURN v_result;
END;
$$;

CREATE OR REPLACE FUNCTION wes_v40_is_allowed_metadata(p_value TEXT)
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
           v_token ~ '^route=(RESOURCE_LIST|RESOURCE_DETAIL|RESOURCE_CONTEXT|RESOURCE_READ|ADMIN_ACCOUNT_LIST|OPERATIONS_OVERVIEW|OBSERVABILITY_LINKS|TRASH_LIST|CHILD_TRASH_LIST|SYSTEM_SETTINGS|AUTH_SESSION|IMPERSONATION_READ|ADMIN_READ|AUDIT_LOG_LIST|REVISION_LIST|AUDIT_LOG_DETAIL|AUDIT_LOG_READ|MUTATION_FAILURE|RESPONSE_FAILURE)$' OR
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

CREATE OR REPLACE FUNCTION wes_v40_canonical_reason(p_value TEXT)
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
    IF wes_v40_is_allowed_metadata(v_value) THEN
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
        'CUSTOMER_REQUEST','DATA_CORRECTION','INCIDENT_RECOVERY','SECURITY_RESPONSE',
        'POLICY_ENFORCEMENT','TEST_OPERATION','OTHER'
    ) THEN
        v_category := 'UNSPECIFIED';
    END IF;
    RETURN 'reasonCategory=' || v_category || ' operatorReasonProvided=true';
END;
$$;

CREATE OR REPLACE FUNCTION wes_v40_safe_target_id(p_target_type TEXT, p_target_id TEXT)
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

CREATE OR REPLACE FUNCTION wes_v40_canonical_target_label(p_target_type TEXT, p_target_id TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE
        WHEN p_target_type IS NULL THEN NULL
        WHEN p_target_type = 'AUTHENTICATION' THEN 'AUTHENTICATION'
        WHEN wes_v40_safe_target_id(p_target_type, p_target_id) IS NULL THEN left(p_target_type, 120)
        ELSE left(p_target_type || ' #' || wes_v40_safe_target_id(p_target_type, p_target_id), 120)
    END
$$;

-- NULL payload를 permanent snapshot에서 되살리지 않는다. 현재 payload에 이미 남은 allowlist만
-- 재검사하고, 기한은 기존 값과 생성 후 7일 중 빠른 쪽으로 단축한다.
UPDATE admin_entity_revisions
SET before_restore_payload = CASE
        WHEN before_restore_payload IS NULL OR
             LEAST(restore_expires_at, created_at + INTERVAL '7 days') <= CURRENT_TIMESTAMP THEN NULL
        ELSE wes_v40_restore_payload(wes_v40_try_json(before_restore_payload), target_type)::TEXT
    END,
    after_restore_payload = CASE
        WHEN after_restore_payload IS NULL OR
             LEAST(restore_expires_at, created_at + INTERVAL '7 days') <= CURRENT_TIMESTAMP THEN NULL
        ELSE wes_v40_restore_payload(wes_v40_try_json(after_restore_payload), target_type)::TEXT
    END,
    -- V32의 expand-contract 기간에는 구/신 컬럼을 함께 줄여 어느 세대 이미지로
    -- rollback해도 같은 restore window를 읽게 한다.
    expires_at = LEAST(restore_expires_at, created_at + INTERVAL '7 days'),
    restore_expires_at = LEAST(restore_expires_at, created_at + INTERVAL '7 days');

UPDATE admin_entity_revisions
SET target_id = COALESCE(wes_v40_safe_target_id(target_type, target_id), left('REDACTED-' || id, 128)),
    target_version = COALESCE(
        target_version,
        wes_v40_safe_bigint(wes_v40_try_json(after_snapshot) ->> 'version'),
        wes_v40_safe_bigint(wes_v40_try_json(before_snapshot) ->> 'version')
    ),
    before_snapshot = CASE WHEN before_snapshot IS NULL THEN NULL ELSE
        COALESCE(wes_v40_sanitize_permanent_snapshot(wes_v40_try_json(before_snapshot))::TEXT, '{}')
    END,
    after_snapshot = CASE WHEN after_snapshot IS NULL THEN NULL ELSE
        COALESCE(wes_v40_sanitize_permanent_snapshot(wes_v40_try_json(after_snapshot))::TEXT, '{}')
    END,
    snapshot_schema_version = 3;

UPDATE admin_audit_logs
SET actor_username_snapshot = CASE
        WHEN actor_admin_id IS NULL THEN NULL ELSE left('ADMIN #' || actor_admin_id, 64)
    END,
    target_label = wes_v40_canonical_target_label(target_type, target_id),
    target_id = wes_v40_safe_target_id(target_type, target_id),
    source_address = NULL,
    reason = wes_v40_canonical_reason(reason),
    changed_fields = wes_v40_sanitize_changed_fields(changed_fields),
    correlation_id = CASE WHEN correlation_id ~ '^[0-9a-f]{16}$' THEN correlation_id ELSE NULL END;

UPDATE admin_auth_events
SET username_snapshot = CASE
        WHEN COALESCE(target_admin_id, actor_admin_id) IS NULL THEN NULL
        ELSE left('ADMIN #' || COALESCE(target_admin_id, actor_admin_id), 64)
    END,
    source_address = NULL,
    reason = wes_v40_canonical_reason(reason);

UPDATE admin_impersonation_sessions
SET target_label = left(target_type || ' #' || target_id, 500),
    reason = wes_v40_canonical_reason(reason),
    source_address = NULL;

-- 휴지통에 남아 있던 사람 이름/고객 자유사유도 상태·ID·승인된 사유 category만 남긴다.
UPDATE admin_trash_batches
SET root_label = left(root_type || ' #' || root_id, 120),
    actor_username = CASE
        WHEN status = 'PURGED' OR actor_admin_id IS NULL THEN NULL
        ELSE left('ADMIN #' || actor_admin_id, 64)
    END,
    reason = CASE
        WHEN status = 'PURGED' THEN 'reasonCategory=UNSPECIFIED operatorReasonProvided=false'
        ELSE wes_v40_canonical_reason(reason)
    END;

UPDATE admin_child_trash_records
SET actor_username = CASE
        WHEN status = 'PURGED' OR actor_admin_id IS NULL THEN NULL
        ELSE left('ADMIN #' || actor_admin_id, 64)
    END,
    reason = CASE
        WHEN status = 'PURGED' THEN 'reasonCategory=UNSPECIFIED operatorReasonProvided=false'
        ELSE wes_v40_canonical_reason(reason)
    END;

ALTER TABLE admin_entity_revisions
    DROP CONSTRAINT IF EXISTS ck_admin_entity_revisions_restore_window;

ALTER TABLE admin_entity_revisions
    ADD CONSTRAINT ck_admin_entity_revisions_restore_window
    CHECK (restore_expires_at <= created_at + INTERVAL '7 days');

CREATE OR REPLACE FUNCTION prevent_admin_entity_revision_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'admin_entity_revisions are immutable';
    END IF;
    IF ROW(
        NEW.id, NEW.target_type, NEW.target_id, NEW.revision_number, NEW.operation,
        NEW.before_snapshot, NEW.after_snapshot, NEW.snapshot_schema_version,
        NEW.target_version, NEW.version, NEW.created_at
    ) IS DISTINCT FROM ROW(
        OLD.id, OLD.target_type, OLD.target_id, OLD.revision_number, OLD.operation,
        OLD.before_snapshot, OLD.after_snapshot, OLD.snapshot_schema_version,
        OLD.target_version, OLD.version, OLD.created_at
    ) THEN
        RAISE EXCEPTION 'permanent admin_entity_revision fields are immutable';
    END IF;
    IF NEW.expires_at IS NULL OR NEW.restore_expires_at IS NULL OR
       NEW.expires_at IS DISTINCT FROM NEW.restore_expires_at OR
       NEW.expires_at > OLD.expires_at OR NEW.restore_expires_at > OLD.restore_expires_at THEN
        RAISE EXCEPTION 'admin_entity_revision restore expiry may only be shortened';
    END IF;
    IF NOT (
        NEW.before_restore_payload IS NOT DISTINCT FROM OLD.before_restore_payload OR
        (OLD.before_restore_payload IS NOT NULL AND NEW.before_restore_payload IS NULL)
    ) OR NOT (
        NEW.after_restore_payload IS NOT DISTINCT FROM OLD.after_restore_payload OR
        (OLD.after_restore_payload IS NOT NULL AND NEW.after_restore_payload IS NULL)
    ) THEN
        RAISE EXCEPTION 'admin_entity_revision restore payload may only be cleared';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_admin_entity_revisions_immutable
    BEFORE UPDATE OR DELETE ON admin_entity_revisions
    FOR EACH ROW
    EXECUTE FUNCTION prevent_admin_entity_revision_mutation();

CREATE TRIGGER trg_admin_audit_logs_immutable
    BEFORE UPDATE OR DELETE ON admin_audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION prevent_admin_audit_log_mutation();

DROP FUNCTION wes_v40_canonical_target_label(TEXT, TEXT);
DROP FUNCTION wes_v40_safe_target_id(TEXT, TEXT);
DROP FUNCTION wes_v40_canonical_reason(TEXT);
DROP FUNCTION wes_v40_is_allowed_metadata(TEXT);
DROP FUNCTION wes_v40_restore_payload(JSONB, TEXT);
DROP FUNCTION wes_v40_scrub_restore_secrets(JSONB);
DROP FUNCTION wes_v40_sanitize_changed_fields(TEXT);
DROP FUNCTION wes_v40_sanitize_permanent_snapshot(JSONB, TEXT);
DROP FUNCTION wes_v40_is_strict_timestamp(TEXT);
DROP FUNCTION wes_v40_audit_key_policy(TEXT);
DROP FUNCTION wes_v40_safe_bigint(TEXT);
DROP FUNCTION wes_v40_try_json(TEXT);

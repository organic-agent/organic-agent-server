package com.soma.wes.admin.audit.support

import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAccessor

@Component
class AdminAuditSnapshotCodec(
    private val objectMapper: ObjectMapper,
    private val sanitizer: AdminAuditSanitizer,
) {

    fun encode(snapshot: Map<String, Any?>?): String? =
        snapshot?.let { objectMapper.writeValueAsString(sanitizeMap(it)) }

    /**
     * 영구 감사 응답과 분리된 7일 한정 복원 자료다. 대상별로 실제 복원 가능한 필드만
     * allowlist하고 credential 패턴은 제거한다. 이 값은 revision API에 노출하지 않는다.
     */
    fun encodeRestorePayload(
        targetType: AdminAuditTargetType,
        snapshot: Map<String, Any?>?,
    ): String? {
        val allowedFields = RESTORE_PAYLOAD_FIELDS[targetType] ?: return null
        val filtered = snapshot.orEmpty()
            .filterKeys(allowedFields::contains)
            .mapValues { (_, value) -> sanitizeRestoreValue(value) }
        return filtered.takeIf { it.isNotEmpty() }
            ?.let(objectMapper::writeValueAsString)
    }

    fun decode(snapshot: String?): JsonNode? = snapshot?.let(objectMapper::readTree)

    /**
     * 신규 allowlist 도입 전에 저장된 영구 스냅샷도 API에서 그대로 노출하지 않는다. 원본 DB
     * 행은 불변으로 두고, 읽을 때 현재의 더 엄격한 정책을 다시 적용한다.
     */
    fun decodePermanent(snapshot: String?): JsonNode? = snapshot?.let {
        val parsed = objectMapper.readValue(it, Map::class.java).entries
            .associate { (key, value) -> key.toString() to value }
        objectMapper.readTree(objectMapper.writeValueAsString(sanitizeMap(parsed)))
    }

    fun changedFields(
        before: Map<String, Any?>?,
        after: Map<String, Any?>?,
    ): List<String> =
        (before.orEmpty().keys + after.orEmpty().keys)
            .distinct()
            .filter(::isPermanentSnapshotKey)
            .filterNot(NON_BUSINESS_FIELDS::contains)
            .filter { before?.get(it) != after?.get(it) }
            .sorted()

    /** 호출자가 직접 넘긴 changed_fields에도 snapshot과 동일한 exact-key 계약을 적용한다. */
    fun sanitizeChangedFields(fields: Collection<String>): List<String> =
        fields.asSequence()
            .filter(::isPermanentSnapshotKey)
            .filterNot(NON_BUSINESS_FIELDS::contains)
            .distinct()
            .sorted()
            .toList()

    /**
     * 영구 snapshot은 운영 상태를 재구성할 구조 증거만 보존한다. 문자열을 정규식으로 일부
     * 가리는 방식은 이름·제목·본문 같은 새 필드가 추가될 때 누락될 수 있으므로, ID/enum/
     * timestamp와 version/count 계열 수치만 exact key schema로 allowlist한다. 알 수 없는 key는
     * 값만 가린 채 남기지 않고 key 자체를 제거해 user-controlled JSON key의 PII 우회도 막는다.
     */
    private fun sanitizeMap(value: Map<String, Any?>): Map<String, Any?> =
        value.entries.mapNotNull { (key, nested) ->
            permanentKeyPolicy(key)?.let { policy -> key to sanitizePermanentValue(key, policy, nested) }
        }.toMap(linkedMapOf())

    private fun sanitizePermanentValue(key: String, policy: PermanentKeyPolicy, value: Any?): Any? {
        if (policy == PermanentKeyPolicy.REDACTED) return REDACTED
        return when (value) {
            null -> null
            is Map<*, *> -> if (policy == PermanentKeyPolicy.CONTAINER) {
                sanitizeMap(
                    value.entries.associate { (nestedKey, nestedValue) -> nestedKey.toString() to nestedValue },
                )
            } else {
                REDACTED
            }
            is Iterable<*> -> value.map { sanitizePermanentValue(key, policy, it) }
            is Array<*> -> value.map { sanitizePermanentValue(key, policy, it) }
            is Boolean -> if (policy == PermanentKeyPolicy.BOOLEAN) value else REDACTED
            is Number -> if (policy == PermanentKeyPolicy.IDENTIFIER || policy == PermanentKeyPolicy.NUMERIC) {
                value
            } else {
                REDACTED
            }
            is Enum<*> -> if (policy == PermanentKeyPolicy.ENUM && SAFE_ENUM_VALUE.matches(value.name)) {
                value.name
            } else {
                REDACTED
            }
            is TemporalAccessor -> value.toString().takeIf {
                policy == PermanentKeyPolicy.TIMESTAMP && isStrictTimestamp(it)
            } ?: REDACTED
            is String -> when {
                policy == PermanentKeyPolicy.IDENTIFIER && SAFE_IDENTIFIER_VALUE.matches(value) -> value
                policy == PermanentKeyPolicy.ENUM && SAFE_ENUM_VALUE.matches(value) -> value
                policy == PermanentKeyPolicy.TIMESTAMP && isStrictTimestamp(value) -> value
                else -> REDACTED
            }
            else -> REDACTED
        }
    }

    private fun isPermanentSnapshotKey(key: String): Boolean = permanentKeyPolicy(key) != null

    private fun permanentKeyPolicy(key: String): PermanentKeyPolicy? = when (key) {
        in IDENTIFIER_KEYS -> PermanentKeyPolicy.IDENTIFIER
        in ENUM_KEYS -> PermanentKeyPolicy.ENUM
        in TIMESTAMP_KEYS -> PermanentKeyPolicy.TIMESTAMP
        in NUMERIC_KEYS, in STRUCTURAL_COUNT_KEYS -> PermanentKeyPolicy.NUMERIC
        in BOOLEAN_KEYS -> PermanentKeyPolicy.BOOLEAN
        in CONTAINER_KEYS -> PermanentKeyPolicy.CONTAINER
        in REDACTED_KEYS -> PermanentKeyPolicy.REDACTED
        else -> null
    }

    private fun isStrictTimestamp(value: String): Boolean {
        val match = SAFE_TIMESTAMP_VALUE.matchEntire(value) ?: return false
        return runCatching {
            OffsetDateTime.parse(match.groups[1]!!.value)
            match.groups[2]?.value?.let(ZoneId::of)
        }.isSuccess
    }

    private fun sanitizeRestoreValue(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> value.entries.associate { (key, nested) ->
                key.toString() to sanitizeRestoreValue(nested)
            }
            is Iterable<*> -> value.map(::sanitizeRestoreValue)
            is Array<*> -> value.map(::sanitizeRestoreValue)
            is String -> sanitizer.sanitizeSecretsOnly(value)
            else -> value
        }

    companion object {
        private const val REDACTED = "[REDACTED]"
        private val NON_BUSINESS_FIELDS = setOf("type", "id", "version", "label")
        private val IDENTIFIER_KEYS = setOf(
            "id", "userId", "ownerUserId", "workspaceId", "studioId", "galleryId", "photoId", "selectionId",
            "conceptFolderId", "detailFolderId", "createdByUserId", "personalOwnerUserId",
            "assignedByUserId", "ratedByUserId",
            "collaborationId", "albumId", "roundId", "retouchPhotoId", "templateId", "memberId",
            "inviteId", "jobId", "revisionId", "selectedRevisionId", "selectionRevisionId",
            "resultRevisionId", "previousRevisionId", "replacementId", "uploadId", "commentId",
            "likeId", "folderId", "ownerId", "previousOwnerId", "actorAdminId", "parentId",
            "childId", "trashBatchId", "childTrashId", "resourceId", "itemId", "groupId",
            "guestId", "notificationOutboxId", "previousJobId", "scopeId",
            "mockRecalculationJobId", "sessionId", "photoIds", "processingJobIds",
        )
        private val ENUM_KEYS = setOf(
            "type", "status", "state", "role", "source", "action", "operation", "outcome",
            "provider", "rootType", "childType", "parentType", "resourceType",
            "workflowAction", "workflowStatus", "publicStatus", "galleryStatus", "selectionStatus",
            "processingStatus", "reprocessStatus", "jobType", "jobStatus", "mockRecalculationStatus",
            "deliveryStatus", "inviteStatus", "artifactType", "notificationType", "trashStatus",
            "failureCode", "capability", "workspaceType", "createdSource", "assignedSource", "mode",
            "stage", "kind", "scope",
        )
        private val TIMESTAMP_KEYS = setOf(
            "lockedUntil", "selectionDeadline", "uploadUrlExpiresAt", "submittedAt", "expiresAt",
            "requestedAt", "completedAt", "restoreUntil", "createdAt", "updatedAt", "deletedAt",
            "lastRunAt", "suspendedAt", "revokedAt", "startedAt", "deliveredAt", "selectedAt",
            "takenAt", "joinedAt", "lastActivityAt", "customerConsentedAt", "inviteExpiresAt",
            "purgeEligibleAt", "assignedAt", "readAt",
        )
        private val NUMERIC_KEYS = setOf(
            "version", "targetVersion", "restoredSnapshotVersion", "failedLoginAttempts", "attemptCount",
            "displayOrder", "maxSelectablePhotoCount", "maxRetouchRoundCount", "roundNo", "itemCount",
            "templateCount", "returnedCount", "totalCount", "restoredCount", "entryCount",
            "rootEntryCount", "commentCount", "albumTemplateReferenceCount", "templateMetadataCount",
            "photoRevisionCount", "selectionRevisionCount", "photoStorageMetadataCount",
            "retouchStorageMetadataCount", "entityRevisionCount", "purgeAttemptCount",
            "uploadUrlTtlSeconds", "terminatedCount", "removedCount", "photoCount", "fieldCount",
            "selectedCount", "remainingCount", "remainingRoundCount", "requestedCount", "targetPhotoCount",
            "byteSize", "storageBytes", "width", "height", "sortOrder", "roundVersion",
            "retouchPhotoVersion", "revisionNumber", "expectedVersion", "restoreWindowDays",
            "detailCount", "processedPhotos", "assignedPhotos", "owners", "ratings",
            "maxUses", "usedCount", "remainingUses",
        )
        private val BOOLEAN_KEYS = setOf(
            "deleted", "revoked", "suspended", "purged", "assigned", "revealed", "reissued", "force",
            "enabled", "present", "submitted", "analyzed", "annotated", "hasResult", "resultReady",
            "previewReady", "requiresEmbedding", "restorable", "oneTimeReveal",
            "canRestoreDirectly", "emailEnabled", "browserEnabled", "settingsPersisted",
        )
        private val CONTAINER_KEYS = setOf(
            "resource", "fields", "facts", "sections", "workflowDetails", "affectedCounts",
            "relationshipFacts", "payloadSummary", "notification", "inputConditions", "comments", "photos",
            "items", "members", "invites", "galleries", "albums", "selections", "collaborations", "folders",
            "rounds", "templates", "likes", "guests", "sessions", "processingJobs", "replacementUploads",
            "revisions", "retouchRounds", "selectedPhotos", "retouchedPhotos", "completedResults",
            "pendingResults", "photoItems", "owner", "mockGallery", "delivery", "retouchCapabilities",
            "albumReferences", "collaborationLinks", "selectionReferences", "retouchReferences",
            "galleryMemberships", "joinedGalleries", "ownedStudios", "notifications",
            "aiJobs", "activeSessions", "workspaces", "conceptFolders", "detailFolders",
            "categoryAssignments", "categorizationJobs", "photoRatings", "categoryAssignment", "rating",
            "userNotifications", "userNotificationSettings",
        )
        private val STRUCTURAL_COUNT_KEYS = setOf(
            "ADMIN_ACCOUNT", "USER", "STUDIO", "GALLERY", "PHOTO", "SELECTION", "COLLABORATION",
            "ALBUM", "RETOUCH_REQUEST", "WORKSPACE", "CONCEPT_FOLDER", "DETAIL_FOLDER",
            "PHOTO_CATEGORY_ASSIGNMENT", "CATEGORIZATION_JOB", "PHOTO_RATING", "WORKSPACE_MEMBER",
            "GALLERY_MEMBER", "COLLAB_COMMENT", "COLLAB_LIKE",
            "ALBUM_TEMPLATE", "RETOUCH_ITEM",
        )
        private val REDACTED_KEYS = setOf(
            "username", "displayName", "nickname", "email", "name", "title", "label", "content",
            "requestText", "deliveryNote", "originalFileName", "fileName", "storageKey", "previewKey",
            "annotationKey", "resultKey", "providerId", "collabToken", "uploadUrl", "downloadUrl",
            "presignedUrl", "galleryUrl", "inflowChannel", "recipientReference", "payload",
            "structuredAiMetadata", "address", "phone", "phoneNumber", "reason", "inviteUrl", "collabUrl",
            "folderName", "templateName", "studioName", "galleryTitle", "token", "embedding", "author",
            "message", "cameraMake", "cameraModel", "layout", "crop", "algorithm", "contentType",
            "resultContentType", "replacement_upload_url", "structured_ai_metadata", "recipient_reference",
            "score", "confidence", "contact", "description",
        )
        private val SAFE_IDENTIFIER_VALUE = Regex(
            "(?:[0-9]+|[0-9a-fA-F]{16}|[0-9a-fA-F]{8}-[0-9a-fA-F-]{27})",
        )
        private val SAFE_ENUM_VALUE = Regex("[A-Z][A-Z0-9_]{0,79}")
        private val SAFE_TIMESTAMP_VALUE = Regex(
            "^([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}" +
                "(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2}))(?:\\[([A-Za-z0-9._+/-]{1,64})])?$",
        )
        private val RESTORE_PAYLOAD_FIELDS = mapOf(
            AdminAuditTargetType.ADMIN_ACCOUNT to setOf(
                "version", "username", "displayName", "status", "failedLoginAttempts", "lockedUntil",
            ),
            AdminAuditTargetType.USER to setOf(
                "type", "id", "version", "deleted", "nickname", "email",
            ),
            AdminAuditTargetType.WORKSPACE to setOf(
                "type", "id", "version", "deleted", "name", "personalOwnerUserId",
            ),
            AdminAuditTargetType.STUDIO to setOf(
                "type", "id", "version", "deleted", "workspaceId", "ownerUserId", "name", "galleryUrl", "contact", "description",
            ),
            AdminAuditTargetType.GALLERY to setOf(
                "type", "id", "version", "deleted", "workspaceId", "createdByUserId", "title", "status", "workflowStatus", "stage",
                "selectionDeadline", "maxSelectablePhotoCount", "maxRetouchRoundCount",
            ),
            AdminAuditTargetType.PHOTO to setOf(
                "type", "id", "version", "deleted", "galleryId", "displayOrder", "status", "uploadUrlExpiresAt",
            ),
            AdminAuditTargetType.CONCEPT_FOLDER to setOf(
                "type", "id", "version", "deleted", "galleryId", "name", "sortOrder", "createdSource",
            ),
            AdminAuditTargetType.DETAIL_FOLDER to setOf(
                "type", "id", "version", "deleted", "conceptFolderId", "name", "sortOrder", "createdSource",
            ),
            AdminAuditTargetType.PHOTO_CATEGORY_ASSIGNMENT to setOf(
                "type", "id", "version", "deleted", "photoId", "detailFolderId", "assignedByUserId",
                "assignedSource", "confidence", "assignedAt",
            ),
            AdminAuditTargetType.PHOTO_RATING to setOf(
                "type", "id", "version", "deleted", "photoId", "score", "ratedByUserId",
            ),
            AdminAuditTargetType.SELECTION to setOf(
                "type", "id", "version", "deleted", "galleryId", "status", "submittedAt",
            ),
            AdminAuditTargetType.COLLABORATION to setOf(
                "type", "id", "version", "deleted", "galleryId", "conceptFolderId", "name", "revoked", "expiresAt",
            ),
            AdminAuditTargetType.ALBUM to setOf(
                "type", "id", "version", "deleted", "galleryId", "name",
            ),
            AdminAuditTargetType.RETOUCH_REQUEST to setOf(
                "type", "id", "version", "deleted", "galleryId", "status", "requestedAt", "completedAt",
            ),
        )

        private enum class PermanentKeyPolicy {
            IDENTIFIER,
            ENUM,
            TIMESTAMP,
            NUMERIC,
            BOOLEAN,
            CONTAINER,
            REDACTED,
        }
    }
}

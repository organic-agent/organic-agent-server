package com.soma.wes.admin.audit.service

import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.domain.AdminEntityRevision
import com.soma.wes.admin.audit.support.AdminAccountAuditSnapshot
import com.soma.wes.admin.audit.support.AdminAuditSnapshotCodec
import com.soma.wes.admin.repository.AdminAccountRepository
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.repository.AdminResourceRepository
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.ZonedDateTime
import tools.jackson.databind.JsonNode

/** API의 `restorable`과 실제 복원 서비스가 공유하는 fail-closed capability 판정. */
@Component
class AdminRevisionRestorePolicy(
    private val revisionCodec: AdminAuditSnapshotCodec,
    private val adminAccountRepository: AdminAccountRepository,
    private val resourceRepository: AdminResourceRepository,
    private val clock: Clock,
) {

    fun isRestorable(revision: AdminEntityRevision): Boolean = runCatching {
        if (!revision.restoreExpiresAt.isAfter(ZonedDateTime.now(clock))) return false
        if (revision.snapshotSchemaVersion != AdminAuditService.CURRENT_SNAPSHOT_SCHEMA_VERSION) return false
        val targetVersion = revision.targetVersion ?: return false
        val payload = revisionCodec.decode(revision.afterRestorePayload) ?: return false
        if (!payload.path("version").isIntegralNumber || payload.path("version").asLong() != targetVersion) return false

        if (revision.targetType == AdminAuditTargetType.ADMIN_ACCOUNT) {
            return adminAccountRestorable(revision.targetId, payload)
        }
        val type = SUPPORTED_RESOURCE_TYPES[revision.targetType] ?: return false
        resourceRestorable(type, revision.targetId, payload)
    }.getOrDefault(false)

    private fun adminAccountRestorable(targetId: String, payload: JsonNode): Boolean {
        val id = targetId.toLongOrNull() ?: return false
        val account = adminAccountRepository.findById(id).orElse(null) ?: return false
        val selected = runCatching { AdminAccountAuditSnapshot.from(payload) }.getOrNull() ?: return false
        if (selected.username != REDACTED && selected.username != account.username) return false
        return selected.displayName != account.displayName ||
            selected.status != account.status ||
            selected.failedLoginAttempts != account.failedLoginAttempts ||
            selected.lockedUntil != account.lockedUntil
    }

    private fun resourceRestorable(type: AdminResourceType, targetId: String, payload: JsonNode): Boolean {
        val id = targetId.toLongOrNull() ?: return false
        val current = resourceRepository.find(type, id) ?: return false
        if (current.deleted || payload.path("type").asText() != type.name ||
            !payload.path("id").isIntegralNumber || payload.path("id").asLong() != id ||
            !payload.path("deleted").isBoolean || payload.path("deleted").asBoolean() != current.deleted
        ) {
            return false
        }
        RELATION_FIELDS.getValue(type).forEach { field ->
            val selected = payload.get(field) ?: return false
            if (!sameScalar(current.fields[field], selected)) return false
        }
        return MUTABLE_FIELDS.getValue(type).any { field ->
            val selected = payload.get(field) ?: return@any false
            if (selected.isTextual && selected.asText() in REDACTED_VALUES) return@any false
            !sameScalar(current.fields[field], selected)
        }
    }

    private fun sameScalar(current: Any?, selected: JsonNode): Boolean = when {
        current == null -> selected.isNull
        current is Number && selected.isNumber -> runCatching {
            BigDecimal(current.toString()).compareTo(BigDecimal(selected.asText())) == 0
        }.getOrDefault(false)
        current is Boolean -> selected.isBoolean && selected.asBoolean() == current
        selected.isTextual -> selected.asText() == current.toString()
        else -> false
    }

    companion object {
        private const val REDACTED = "[REDACTED]"
        private val REDACTED_VALUES = setOf(REDACTED, "[MASKED]")
        private val SUPPORTED_RESOURCE_TYPES = mapOf(
            AdminAuditTargetType.USER to AdminResourceType.USER,
            AdminAuditTargetType.WORKSPACE to AdminResourceType.WORKSPACE,
            AdminAuditTargetType.STUDIO to AdminResourceType.STUDIO,
            AdminAuditTargetType.GALLERY to AdminResourceType.GALLERY,
            AdminAuditTargetType.PHOTO to AdminResourceType.PHOTO,
            AdminAuditTargetType.CONCEPT_FOLDER to AdminResourceType.CONCEPT_FOLDER,
            AdminAuditTargetType.DETAIL_FOLDER to AdminResourceType.DETAIL_FOLDER,
            AdminAuditTargetType.PHOTO_RATING to AdminResourceType.PHOTO_RATING,
            AdminAuditTargetType.COLLABORATION to AdminResourceType.COLLABORATION,
        )
        private val RELATION_FIELDS = mapOf(
            AdminResourceType.USER to emptySet(),
            AdminResourceType.WORKSPACE to setOf("type", "personalOwnerUserId"),
            AdminResourceType.STUDIO to setOf("workspaceId", "ownerUserId"),
            AdminResourceType.GALLERY to setOf("workspaceId"),
            AdminResourceType.PHOTO to setOf("galleryId"),
            AdminResourceType.CONCEPT_FOLDER to setOf("galleryId"),
            AdminResourceType.DETAIL_FOLDER to setOf("conceptFolderId"),
            AdminResourceType.PHOTO_RATING to setOf("photoId"),
            AdminResourceType.COLLABORATION to setOf("galleryId", "conceptFolderId"),
        )
        private val MUTABLE_FIELDS = mapOf(
            AdminResourceType.USER to setOf("nickname", "email"),
            AdminResourceType.WORKSPACE to setOf("name"),
            AdminResourceType.STUDIO to setOf("name", "galleryUrl", "contact", "description"),
            AdminResourceType.GALLERY to setOf("title", "maxSelectablePhotoCount", "maxRetouchRoundCount"),
            AdminResourceType.PHOTO to setOf("displayOrder", "status", "uploadUrlExpiresAt"),
            AdminResourceType.CONCEPT_FOLDER to setOf("name", "sortOrder"),
            AdminResourceType.DETAIL_FOLDER to setOf("name", "sortOrder"),
            AdminResourceType.PHOTO_RATING to setOf("score", "ratedByUserId"),
            AdminResourceType.COLLABORATION to setOf("name"),
        )
    }
}

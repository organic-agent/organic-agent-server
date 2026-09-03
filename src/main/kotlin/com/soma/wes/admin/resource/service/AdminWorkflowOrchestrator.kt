package com.soma.wes.admin.resource.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.service.AdminAuditService
import com.soma.wes.admin.audit.support.AdminAuditSanitizer
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.domain.AdminChildTrashType
import com.soma.wes.admin.resource.domain.AdminInboxEventType
import com.soma.wes.admin.resource.dto.AdminResourceResponse
import com.soma.wes.admin.resource.dto.AdminWorkflowAction
import com.soma.wes.admin.resource.dto.AdminWorkflowRequest
import com.soma.wes.admin.resource.dto.AdminWorkflowResponse
import com.soma.wes.admin.resource.dto.AdminRetouchArtifactType
import com.soma.wes.admin.resource.repository.AdminRetouchArtifactRepository
import com.soma.wes.admin.resource.repository.AdminResourceContextRepository
import com.soma.wes.admin.resource.repository.AdminResourceRepository
import com.soma.wes.admin.resource.repository.AdminWorkflowRepository
import com.soma.wes.admin.resource.repository.AdminNotificationInboxRepository
import com.soma.wes.category.service.CategorizationService
import com.soma.wes.collab.support.CollabLinkResolver
import com.soma.wes.gallery.domain.GalleryInviteKind
import com.soma.wes.gallery.service.GalleryInviteService
import com.soma.wes.gallery.support.GalleryInviteUrlResolver
import com.soma.wes.global.SecureTokenGenerator
import com.soma.wes.global.filter.HttpLoggingFilter
import com.soma.wes.photo.service.PhotoStorage
import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.TreeMap

/**
 * 최고 관리자 워크플로의 트랜잭션 경계다.
 *
 * S3/Lambda 호출은 이 클래스의 transactionTemplate 블록 밖에서만 실행한다. DB에 예약/결과를
 * 먼저 남기므로 네트워크 타임아웃이 비즈니스 행 잠금을 붙들지 않고, 같은 멱등성 키의 재전송도
 * 외부 호출을 반복하지 않는다.
 */
@Service
class AdminWorkflowService(
    private val workflowRepository: AdminWorkflowRepository,
    private val notificationInboxRepository: AdminNotificationInboxRepository,
    private val retouchArtifactRepository: AdminRetouchArtifactRepository,
    private val childTrashService: AdminChildTrashService,
    private val resourceRepository: AdminResourceRepository,
    private val contextRepository: AdminResourceContextRepository,
    private val auditService: AdminAuditService,
    private val auditSanitizer: AdminAuditSanitizer,
    private val photoStorage: PhotoStorage,
    private val tokenGenerator: SecureTokenGenerator,
    private val galleryInviteUrlResolver: GalleryInviteUrlResolver,
    private val collabLinkResolver: CollabLinkResolver,
    private val categorizationService: CategorizationService,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) {

    fun execute(
        actorAdminId: Long,
        type: AdminResourceType,
        id: Long,
        request: AdminWorkflowRequest,
        sourceAddress: String?,
    ): AdminWorkflowResponse {
        validateRequest(type, request)
        val initial = resourceRepository.find(type, id)
            ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        val actionKey = "WORKFLOW_${request.action.name}"
        val requestHash = requestHash(type, id, request)

        // 재처리는 admin_processing_jobs 자체가 durable outbox다. 예약부터 PENDING 재등록,
        // 감사, 응답 저장까지 한 트랜잭션에 넣어 외부 호출이나 고착된 PENDING 예약을 만들지 않는다.
        if (request.action == AdminWorkflowAction.RETRY_PROCESSING_JOB) {
            return retryProcessingJob(
                actorAdminId = actorAdminId,
                type = type,
                id = id,
                request = request,
                sourceAddress = sourceAddress,
                actionKey = actionKey,
                requestHash = requestHash,
            )
        }

        val reservation = transactionTemplate.execute {
            workflowRepository.reserveWorkflow(actionKey, request.idempotencyKey, requestHash, type, id)
        } ?: error("workflow reservation transaction returned null")

        reservation.requestHash?.let { existingHash ->
            if (existingHash != requestHash) throw AdminException(AdminErrorCode.IDEMPOTENCY_KEY_REUSED)
            if (reservation.status == "COMPLETED" && reservation.resultPayload != null) {
                val stored = objectMapper.readValue(reservation.resultPayload, AdminWorkflowResponse::class.java)
                return when (request.action) {
                    AdminWorkflowAction.ISSUE_PHOTO_REPLACEMENT -> replayPhotoReplacementUpload(id, stored)
                    AdminWorkflowAction.ISSUE_RETOUCH_ARTIFACT_UPLOAD -> replayRetouchArtifactUpload(id, stored)
                    else -> stored.copy(replayed = true)
                }
            }
            throw AdminException(AdminErrorCode.REPROCESS_ALREADY_REQUESTED)
        }
        val childRestore = request.action in CHILD_RESTORE_ACTIONS
        if (!childRestore && (initial.version != request.expectedVersion || initial.deleted)) {
            failReservation(actionKey, request, "RESOURCE_VERSION_CONFLICT")
            throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }

        val before = transactionTemplate.execute { snapshot(type, id, request.action) }
            ?: error("workflow snapshot transaction returned null")
        return try {
            val prepared = prepareExternalWork(type, id, request)
            transactionTemplate.execute {
                val execution = executeDatabaseAction(actorAdminId, type, id, request, prepared)
                val response = AdminWorkflowResponse(
                    action = request.action,
                    targetType = type,
                    targetId = id,
                    idempotencyKey = request.idempotencyKey,
                    status = execution.status,
                    details = execution.details,
                )
                val after = snapshot(type, id, request.action) + mapOf(
                    "workflowStatus" to execution.status,
                    "workflowDetails" to execution.details.toAuditDetails(),
                )
                audit(actorAdminId, type, id, initial.label, request, sourceAddress, before, after)
                workflowRepository.completeWorkflow(
                    actionKey,
                    request.idempotencyKey,
                    objectMapper.writeValueAsString(response.forIdempotentReplay()),
                )
                response
            } ?: error("workflow mutation transaction returned null")
        } catch (error: RuntimeException) {
            failReservation(actionKey, request, error.javaClass.simpleName)
            throw error
        }
    }

    private fun prepareExternalWork(
        type: AdminResourceType,
        id: Long,
        request: AdminWorkflowRequest,
    ): PreparedExternalWork = when (request.action) {
        AdminWorkflowAction.ISSUE_PHOTO_REPLACEMENT -> {
            requireType(type, AdminResourceType.PHOTO)
            val fileName = request.text("fileName", 255)
            val contentType = request.text("contentType", 100).lowercase()
            if (contentType !in ALLOWED_IMAGE_TYPES) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            val photo = resourceRepository.find(type, id) ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
            val galleryId = (photo.fields["galleryId"] as? Number)?.toLong()
                ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            val storageKey = photoStorage.buildKey(galleryId, fileName)
            val upload = photoStorage.presignUpload(storageKey, contentType)
            PreparedExternalWork.Upload(storageKey, fileName, contentType, upload.url, upload.expiresAt)
        }
        AdminWorkflowAction.COMPLETE_PHOTO_REPLACEMENT -> {
            requireType(type, AdminResourceType.PHOTO)
            val replacementId = request.long("replacementId")
            val storageKey = transactionTemplate.execute {
                workflowRepository.pendingPhotoReplacementStorageKey(id, replacementId)
            } ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
            if (!photoStorage.exists(storageKey)) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            PreparedExternalWork.VerifiedReplacement(replacementId)
        }
        AdminWorkflowAction.ISSUE_RETOUCH_ARTIFACT_UPLOAD -> {
            requireType(type, AdminResourceType.RETOUCH_REQUEST)
            val retouchPhotoId = request.long("retouchPhotoId")
            val artifactType = request.retouchArtifactType()
            val fileName = request.safeFileName("fileName")
            val contentType = request.text("contentType", 100).lowercase()
            validateRetouchArtifactContentType(artifactType, contentType)
            val scope = transactionTemplate.execute {
                retouchArtifactRepository.scope(id, retouchPhotoId)
            } ?: error("retouch artifact scope transaction returned null")
            val storageKey = buildRetouchArtifactKey(
                scope.galleryId,
                scope.roundNo,
                retouchPhotoId,
                artifactType,
                contentType,
            )
            val upload = photoStorage.presignUpload(storageKey, contentType)
            PreparedExternalWork.RetouchArtifactUpload(
                retouchPhotoId = retouchPhotoId,
                artifactType = artifactType,
                storageKey = storageKey,
                fileName = fileName,
                contentType = contentType,
                url = upload.url,
                expiresAt = upload.expiresAt,
            )
        }
        AdminWorkflowAction.COMPLETE_RETOUCH_ARTIFACT_UPLOAD -> {
            requireType(type, AdminResourceType.RETOUCH_REQUEST)
            val uploadId = request.long("uploadId")
            val retouchPhotoId = request.long("retouchPhotoId")
            val pending = transactionTemplate.execute {
                retouchArtifactRepository.pendingUpload(id, retouchPhotoId, uploadId)
            } ?: error("retouch artifact pending upload transaction returned null")
            if (!photoStorage.exists(pending.storageKey)) {
                throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            }
            PreparedExternalWork.VerifiedRetouchArtifact(uploadId, retouchPhotoId)
        }
        else -> PreparedExternalWork.None
    }

    /**
     * 서명 URL은 멱등 결과에 저장하지 않는다. 동일 요청 재생은 pending 행의 비밀이 아닌
     * storage key/content type만 짧게 읽고, DB transaction 밖에서 새 URL을 서명한다.
     */
    private fun replayPhotoReplacementUpload(
        photoId: Long,
        stored: AdminWorkflowResponse,
    ): AdminWorkflowResponse {
        val replacementId = (stored.details["replacementId"] as? Number)?.toLong()
            ?: stored.details["replacementId"]?.toString()?.toLongOrNull()
            ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        val pending = transactionTemplate.execute {
            workflowRepository.pendingPhotoReplacementUpload(photoId, replacementId)
        } ?: error("replacement replay transaction returned null")
        val upload = photoStorage.presignUpload(pending.storageKey, pending.contentType)
        return stored.copy(
            replayed = true,
            details = linkedMapOf(
                "replacementId" to replacementId,
                "uploadUrl" to upload.url,
                "expiresAt" to upload.expiresAt,
                "revealed" to true,
                "reissued" to true,
            ),
        )
    }

    /** 저장된 멱등 결과에는 upload id와 버전만 두고 재전송 때마다 새 PUT URL을 서명한다. */
    private fun replayRetouchArtifactUpload(
        roundId: Long,
        stored: AdminWorkflowResponse,
    ): AdminWorkflowResponse {
        val uploadId = stored.detailLong("uploadId")
        val retouchPhotoId = stored.detailLong("retouchPhotoId")
        val pending = transactionTemplate.execute {
            retouchArtifactRepository.pendingUpload(roundId, retouchPhotoId, uploadId)
        } ?: error("retouch artifact replay transaction returned null")
        val upload = photoStorage.presignUpload(pending.storageKey, pending.contentType)
        return stored.copy(
            replayed = true,
            details = stored.details + mapOf(
                "uploadUrl" to upload.url,
                "expiresAt" to upload.expiresAt,
                "revealed" to true,
                "reissued" to true,
            ),
        )
    }

    private fun executeDatabaseAction(
        actorAdminId: Long,
        type: AdminResourceType,
        id: Long,
        request: AdminWorkflowRequest,
        prepared: PreparedExternalWork,
    ): WorkflowExecution = when (request.action) {
        AdminWorkflowAction.TERMINATE_USER_SESSIONS -> {
            requireType(type, AdminResourceType.USER)
            WorkflowExecution(details = mapOf(
                "terminatedCount" to workflowRepository.terminateUserSessions(id, request.expectedVersion),
            ))
        }
        AdminWorkflowAction.SET_STUDIO_OWNER -> {
            requireType(type, AdminResourceType.STUDIO)
            val result = workflowRepository.setStudioOwner(id, request.long("userId"), request.expectedVersion)
            WorkflowExecution(
                details = mapOf(
                    "previousOwnerId" to result.previousOwnerId,
                    "ownerId" to result.ownerId,
                ),
            )
        }
        AdminWorkflowAction.ADD_STUDIO_MEMBER -> {
            requireType(type, AdminResourceType.STUDIO)
            val result = workflowRepository.addStudioMember(id, request.long("userId"), request.expectedVersion)
            WorkflowExecution(
                details = mapOf("memberId" to result.memberId),
            )
        }
        AdminWorkflowAction.REMOVE_STUDIO_MEMBER -> {
            requireType(type, AdminResourceType.STUDIO)
            val memberId = request.long("memberId")
            workflowRepository.removeStudioMember(id, memberId, request.expectedVersion)
            WorkflowExecution(details = mapOf("memberId" to memberId))
        }
        AdminWorkflowAction.ADD_GALLERY_MEMBER -> {
            requireType(type, AdminResourceType.GALLERY)
            val result = workflowRepository.addGalleryMember(id, request.long("userId"), request.expectedVersion)
            WorkflowExecution(
                details = mapOf("memberId" to result.memberId),
            )
        }
        AdminWorkflowAction.REMOVE_GALLERY_MEMBER -> {
            requireType(type, AdminResourceType.GALLERY)
            WorkflowExecution(details = mapOf(
                "removedCount" to workflowRepository.removeGalleryMember(id, request.long("memberId"), request.expectedVersion),
            ))
        }
        AdminWorkflowAction.UPDATE_GALLERY_STATES -> {
            requireType(type, AdminResourceType.GALLERY)
            val publicStatus = request.text("publicStatus", 20).uppercase()
            val workflowStatus = request.text("workflowStatus", 20).uppercase()
            val deadline = request.optionalOffsetDateTime("selectionDeadline")
            if (deadline != null && !deadline.isAfter(ZonedDateTime.now(clock).toOffsetDateTime())) {
                throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            }
            workflowRepository.updateGalleryStates(id, publicStatus, workflowStatus, deadline, request.expectedVersion)
            WorkflowExecution(details = mapOf(
                "publicStatus" to publicStatus,
                "workflowStatus" to workflowStatus,
                "selectionDeadline" to deadline,
            ))
        }
        AdminWorkflowAction.RUN_CATEGORIZATION -> {
            requireType(type, AdminResourceType.GALLERY)
            val result = categorizationService.runAsAdmin(id)
            workflowRepository.bumpResourceVersion(type, id, request.expectedVersion)
            WorkflowExecution(details = mapOf(
                "jobId" to result.id,
                "mode" to result.mode.name,
                "jobStatus" to result.status.name,
                "processedPhotoCount" to result.processedPhotoCount,
            ))
        }
        AdminWorkflowAction.REISSUE_GALLERY_INVITE -> {
            requireType(type, AdminResourceType.GALLERY)
            val now = ZonedDateTime.now(clock)
            val expiresAt = request.optionalOffsetDateTime("expiresAt")?.toZonedDateTime()
                ?: now.plus(GalleryInviteService.VALIDITY)
            if (!expiresAt.isAfter(now)) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            val kind = request.optionalText("kind", 30)?.uppercase()?.let { value ->
                runCatching { GalleryInviteKind.valueOf(value) }
                    .getOrElse { throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS) }
            }
            val maxUses = request.optionalInt("maxUses")
            if (maxUses != null && maxUses !in 1..100) {
                throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            }
            val token = tokenGenerator.generate()
            val invite = workflowRepository.reissueGalleryInvite(
                id, token, kind, maxUses, expiresAt, request.expectedVersion,
            )
            WorkflowExecution(details = mapOf(
                "inviteId" to invite.inviteId,
                "kind" to invite.kind.name,
                "maxUses" to invite.maxUses,
                "usedCount" to 0,
                "expiresAt" to expiresAt,
                "inviteUrl" to galleryInviteUrlResolver.resolve(token),
                "revealed" to true,
            ))
        }
        AdminWorkflowAction.REVOKE_GALLERY_INVITE -> {
            requireType(type, AdminResourceType.GALLERY)
            val inviteId = request.long("inviteId")
            workflowRepository.revokeGalleryInvite(id, inviteId, request.expectedVersion)
            WorkflowExecution(details = mapOf("inviteId" to inviteId))
        }
        AdminWorkflowAction.REISSUE_COLLAB_LINK -> {
            requireType(type, AdminResourceType.COLLABORATION)
            val ttlSeconds = request.optionalLong("ttlSeconds") ?: DEFAULT_COLLAB_TTL_SECONDS
            if (ttlSeconds !in 300..MAX_COLLAB_TTL_SECONDS) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            val expiresAt = ZonedDateTime.now(clock).plusSeconds(ttlSeconds).toOffsetDateTime()
            val token = tokenGenerator.generate()
            workflowRepository.reissueCollabLink(id, token, expiresAt, request.expectedVersion)
            WorkflowExecution(details = mapOf(
                "expiresAt" to expiresAt,
                "collabUrl" to collabLinkResolver.resolve(token),
                "revealed" to true,
            ))
        }
        AdminWorkflowAction.REVOKE_COLLAB_LINK -> {
            requireType(type, AdminResourceType.COLLABORATION)
            workflowRepository.revokeCollabLink(id, request.expectedVersion)
            WorkflowExecution()
        }
        AdminWorkflowAction.REOPEN_GALLERY -> reopenGallery(type, id, request)
        AdminWorkflowAction.SUBMIT_GALLERY -> galleryTransition(
            type, id, request, AdminWorkflowRepository.GalleryTransition.SUBMIT,
        )
        AdminWorkflowAction.COMPLETE_GALLERY -> galleryTransition(
            type, id, request, AdminWorkflowRepository.GalleryTransition.COMPLETE,
        )
        AdminWorkflowAction.ISSUE_PHOTO_REPLACEMENT -> {
            val upload = prepared as? PreparedExternalWork.Upload
                ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            val replacementId = workflowRepository.createPhotoReplacement(
                photoId = id,
                storageKey = upload.storageKey,
                originalFileName = upload.fileName,
                contentType = upload.contentType,
                expiresAt = upload.expiresAt.atZone(ZonedDateTime.now(clock).zone),
                actorAdminId = actorAdminId,
                reason = durableReason(request),
                expectedVersion = request.expectedVersion,
            )
            WorkflowExecution(details = mapOf(
                "replacementId" to replacementId,
                "uploadUrl" to upload.url,
                "expiresAt" to upload.expiresAt,
                "revealed" to true,
            ))
        }
        AdminWorkflowAction.COMPLETE_PHOTO_REPLACEMENT -> {
            val verified = prepared as? PreparedExternalWork.VerifiedReplacement
                ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            val result = workflowRepository.completePhotoReplacement(
                id,
                verified.replacementId,
                actorAdminId,
                durableReason(request),
                request.expectedVersion,
            )
            WorkflowExecution(details = mapOf(
                "replacementId" to verified.replacementId,
                "revisionId" to result.revisionId,
                "revisionNumber" to result.revisionNumber,
                "processingJobIds" to result.jobIds,
                "processingStatus" to "PENDING",
            ))
        }
        AdminWorkflowAction.CANCEL_PROCESSING_JOB -> cancelProcessingJob(type, id, request)
        AdminWorkflowAction.RESEND_NOTIFICATION -> resendNotification(actorAdminId, type, id, request)
        AdminWorkflowAction.CREATE_AI_SELECTION_DRAFT -> createAiDraft(actorAdminId, type, id, request, null)
        AdminWorkflowAction.RETRY_AI_SELECTION_JOB -> {
            requireType(type, AdminResourceType.SELECTION)
            val previousJobId = request.long("jobId")
            workflowRepository.requireRetryableAiJob(id, previousJobId)
            createAiDraft(actorAdminId, type, id, request, previousJobId)
        }
        AdminWorkflowAction.CANCEL_AI_SELECTION_JOB -> {
            requireType(type, AdminResourceType.SELECTION)
            val jobId = request.long("jobId")
            if (workflowRepository.cancelAiSelectionJob(id, jobId) != 1) {
                throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            }
            workflowRepository.bumpResourceVersion(type, id, request.expectedVersion)
            WorkflowExecution(details = mapOf("jobId" to jobId, "jobStatus" to "CANCELED"))
        }
        AdminWorkflowAction.REPLACE_SELECTION_ITEMS -> {
            requireType(type, AdminResourceType.SELECTION)
            val result = workflowRepository.replaceSelectionItems(
                id, request.longs("photoIds"), actorAdminId, durableReason(request), request.expectedVersion,
            )
            WorkflowExecution(details = mapOf(
                "previousRevisionId" to result.previousRevisionId,
                "revisionId" to result.revisionId,
                "photoCount" to result.photoIds.size,
            ))
        }
        AdminWorkflowAction.SUBMIT_SELECTION_REVISION -> {
            requireType(type, AdminResourceType.SELECTION)
            val result = workflowRepository.submitSelectionRevision(
                id,
                request.optionalLong("revisionId"),
                actorAdminId,
                durableReason(request),
                request.expectedVersion,
            )
            WorkflowExecution(status = "PENDING", details = mapOf(
                "previousRevisionId" to result.previousRevisionId,
                "revisionId" to result.revisionId,
                "photoCount" to result.photoIds.size,
                "mockRecalculationJobId" to result.mockJobId,
                "mockRecalculationStatus" to "PENDING",
            ))
        }
        AdminWorkflowAction.WITHDRAW_SELECTION -> {
            requireType(type, AdminResourceType.SELECTION)
            WorkflowExecution(details = mapOf(
                "revisionId" to workflowRepository.withdrawSelection(
                    id, actorAdminId, durableReason(request), request.expectedVersion,
                ),
            ))
        }
        AdminWorkflowAction.CREATE_COLLAB_COMMENT -> {
            requireType(type, AdminResourceType.COLLABORATION)
            WorkflowExecution(details = mapOf(
                "commentId" to workflowRepository.createCollabComment(
                    id,
                    request.long("photoId"),
                    request.long("guestId"),
                    request.text("content", 500),
                    request.expectedVersion,
                ),
            ))
        }
        AdminWorkflowAction.UPDATE_COLLAB_COMMENT -> {
            requireType(type, AdminResourceType.COLLABORATION)
            val commentId = request.long("commentId")
            workflowRepository.updateCollabComment(
                id,
                commentId,
                request.text("content", 500),
                request.expectedVersion,
                request.long("commentExpectedVersion"),
            )
            WorkflowExecution(details = mapOf("commentId" to commentId))
        }
        AdminWorkflowAction.DELETE_COLLAB_COMMENT,
        AdminWorkflowAction.RESTORE_COLLAB_COMMENT,
        -> {
            requireType(type, AdminResourceType.COLLABORATION)
            val commentId = request.long("commentId")
            val commentExpectedVersion = request.long("commentExpectedVersion")
            val result = if (request.action == AdminWorkflowAction.DELETE_COLLAB_COMMENT) {
                childTrashService.delete(
                    actorAdminId = actorAdminId,
                    type = AdminChildTrashType.COLLAB_COMMENT,
                    resourceId = commentId,
                    parentId = id,
                    expectedParentVersion = request.expectedVersion,
                    expectedChildVersion = commentExpectedVersion,
                    reason = durableReason(request),
                )
            } else {
                childTrashService.restore(
                    type = AdminChildTrashType.COLLAB_COMMENT,
                    resourceId = commentId,
                    parentId = id,
                    expectedParentVersion = request.expectedVersion,
                    expectedChildVersion = commentExpectedVersion,
                )
            }
            WorkflowExecution(details = result.workflowDetails() + ("commentId" to commentId))
        }
        AdminWorkflowAction.ADD_COLLAB_LIKE -> {
            requireType(type, AdminResourceType.COLLABORATION)
            val likeId = workflowRepository.addCollabLike(
                id,
                request.long("photoId"),
                request.long("guestId"),
                request.expectedVersion,
            )
            WorkflowExecution(details = mapOf("likeId" to likeId))
        }
        AdminWorkflowAction.REMOVE_COLLAB_LIKE -> {
            requireType(type, AdminResourceType.COLLABORATION)
            val likeId = workflowRepository.findActiveCollabLikeId(
                id,
                request.long("photoId"),
                request.long("guestId"),
            )
            val result = childTrashService.delete(
                actorAdminId = actorAdminId,
                type = AdminChildTrashType.COLLAB_LIKE,
                resourceId = likeId,
                parentId = id,
                expectedParentVersion = request.expectedVersion,
                expectedChildVersion = request.long("likeExpectedVersion"),
                reason = durableReason(request),
            )
            WorkflowExecution(details = result.workflowDetails() + ("likeId" to likeId))
        }
        AdminWorkflowAction.RESTORE_COLLAB_LIKE -> {
            requireType(type, AdminResourceType.COLLABORATION)
            val likeId = request.long("likeId")
            val result = childTrashService.restore(
                type = AdminChildTrashType.COLLAB_LIKE,
                resourceId = likeId,
                parentId = id,
                expectedParentVersion = request.expectedVersion,
                expectedChildVersion = request.long("likeExpectedVersion"),
            )
            WorkflowExecution(details = result.workflowDetails() + ("likeId" to likeId))
        }
        AdminWorkflowAction.CREATE_ALBUM_TEMPLATE -> {
            requireType(type, AdminResourceType.ALBUM)
            val templateId = workflowRepository.createAlbumTemplate(
                id, request.text("name", 100), request.map("layout"), request.expectedVersion,
            )
            WorkflowExecution(details = mapOf("templateId" to templateId, "albumId" to id, "assigned" to true))
        }
        AdminWorkflowAction.UPDATE_ALBUM_TEMPLATE -> {
            requireType(type, AdminResourceType.ALBUM)
            val templateId = request.long("templateId")
            workflowRepository.updateAlbumTemplate(
                id,
                templateId,
                request.long("templateExpectedVersion"),
                request.text("name", 100),
                request.map("layout"),
                request.expectedVersion,
            )
            WorkflowExecution(details = mapOf("templateId" to templateId, "albumId" to id))
        }
        AdminWorkflowAction.DELETE_ALBUM_TEMPLATE,
        AdminWorkflowAction.RESTORE_ALBUM_TEMPLATE,
        -> albumTemplateDeletion(actorAdminId, type, id, request)
        AdminWorkflowAction.REPLACE_ALBUM_LAYOUT -> {
            requireType(type, AdminResourceType.ALBUM)
            val itemCount = workflowRepository.replaceAlbumLayout(
                id,
                request.optionalLong("templateId"),
                request.optionalLong("selectionRevisionId"),
                request.albumFolders(),
                request.expectedVersion,
            )
            WorkflowExecution(details = mapOf("itemCount" to itemCount))
        }
        AdminWorkflowAction.CREATE_RETOUCH_ITEM -> {
            requireType(type, AdminResourceType.RETOUCH_REQUEST)
            val itemId = workflowRepository.createRetouchItem(
                id,
                request.long("photoId"),
                request.optionalText("requestText", 2000),
                request.optionalMap("structuredAiMetadata"),
                request.expectedVersion,
            )
            WorkflowExecution(details = mapOf("retouchPhotoId" to itemId))
        }
        AdminWorkflowAction.UPDATE_RETOUCH_ITEM -> {
            requireType(type, AdminResourceType.RETOUCH_REQUEST)
            val retouchPhotoId = request.long("retouchPhotoId")
            workflowRepository.updateRetouchItem(
                id,
                retouchPhotoId,
                request.retouchItemPatch(),
                request.expectedVersion,
                request.long("retouchPhotoExpectedVersion"),
            )
            WorkflowExecution(details = mapOf("retouchPhotoId" to retouchPhotoId))
        }
        AdminWorkflowAction.ISSUE_RETOUCH_ARTIFACT_UPLOAD -> {
            requireType(type, AdminResourceType.RETOUCH_REQUEST)
            val upload = prepared as? PreparedExternalWork.RetouchArtifactUpload
                ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            val created = retouchArtifactRepository.createUpload(
                roundId = id,
                retouchPhotoId = upload.retouchPhotoId,
                artifactType = upload.artifactType,
                storageKey = upload.storageKey,
                originalFileName = upload.fileName,
                contentType = upload.contentType,
                expiresAt = upload.expiresAt.atZone(ZonedDateTime.now(clock).zone),
                actorAdminId = actorAdminId,
                reason = durableReason(request),
                expectedRoundVersion = request.expectedVersion,
                expectedPhotoVersion = request.long("retouchPhotoExpectedVersion"),
            )
            WorkflowExecution(details = mapOf(
                "uploadId" to created.uploadId,
                "retouchPhotoId" to upload.retouchPhotoId,
                "artifactType" to upload.artifactType.name,
                "roundVersion" to created.roundVersion,
                "retouchPhotoVersion" to created.retouchPhotoVersion,
                "uploadUrl" to upload.url,
                "expiresAt" to upload.expiresAt,
                "revealed" to true,
            ))
        }
        AdminWorkflowAction.COMPLETE_RETOUCH_ARTIFACT_UPLOAD -> {
            requireType(type, AdminResourceType.RETOUCH_REQUEST)
            val verified = prepared as? PreparedExternalWork.VerifiedRetouchArtifact
                ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            val completed = retouchArtifactRepository.completeUpload(
                roundId = id,
                retouchPhotoId = verified.retouchPhotoId,
                uploadId = verified.uploadId,
                expectedRoundVersion = request.expectedVersion,
                expectedPhotoVersion = request.long("retouchPhotoExpectedVersion"),
            )
            WorkflowExecution(details = mapOf(
                "uploadId" to completed.uploadId,
                "retouchPhotoId" to verified.retouchPhotoId,
                "artifactType" to completed.artifactType.name,
                "contentType" to completed.contentType,
                "roundVersion" to completed.roundVersion,
                "retouchPhotoVersion" to completed.retouchPhotoVersion,
            ))
        }
        AdminWorkflowAction.DELETE_RETOUCH_ITEM,
        AdminWorkflowAction.RESTORE_RETOUCH_ITEM,
        -> retouchItemDeletion(actorAdminId, type, id, request)
        AdminWorkflowAction.UPDATE_RETOUCH_DELIVERY -> {
            requireType(type, AdminResourceType.RETOUCH_REQUEST)
            workflowRepository.updateRetouchDelivery(
                id,
                request.boolean("consented"),
                request.boolean("delivered"),
                request.optionalText("deliveryNote", 500),
                request.optionalLong("selectionRevisionId"),
                request.expectedVersion,
            )
            WorkflowExecution()
        }
        AdminWorkflowAction.SET_STUDIO_RETOUCH_CAPABILITY -> {
            requireType(type, AdminResourceType.STUDIO)
            workflowRepository.setStudioRetouchCapability(
                id,
                request.text("capability", 100),
                request.boolean("enabled"),
                request.expectedVersion,
            )
            WorkflowExecution()
        }
        AdminWorkflowAction.RETRY_PROCESSING_JOB -> error("retry is dispatched outside the database action")
    }

    private fun galleryTransition(
        type: AdminResourceType,
        id: Long,
        request: AdminWorkflowRequest,
        transition: AdminWorkflowRepository.GalleryTransition,
    ): WorkflowExecution {
        requireType(type, AdminResourceType.GALLERY)
        workflowRepository.transitionGallery(id, transition, request.expectedVersion)
        return WorkflowExecution(details = mapOf(
            "publicStatus" to transition.publicStatus,
            "workflowStatus" to transition.workflowStatus,
            "stage" to transition.stage,
        ))
    }

    private fun reopenGallery(
        type: AdminResourceType,
        id: Long,
        request: AdminWorkflowRequest,
    ): WorkflowExecution {
        requireType(type, AdminResourceType.GALLERY)
        val selectionDeadline = request.optionalOffsetDateTime("selectionDeadline")
            ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        if (!selectionDeadline.isAfter(ZonedDateTime.now(clock).toOffsetDateTime())) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        workflowRepository.reopenGallery(id, selectionDeadline, request.expectedVersion)
        return WorkflowExecution(details = mapOf(
            "publicStatus" to "OPEN",
            "workflowStatus" to "IN_PROGRESS",
            "stage" to "SELECTION_IN_PROGRESS",
            "selectionDeadline" to selectionDeadline,
        ))
    }

    private fun cancelProcessingJob(
        type: AdminResourceType,
        id: Long,
        request: AdminWorkflowRequest,
    ): WorkflowExecution {
        val jobId = request.long("jobId")
        val job = workflowRepository.findProcessingJob(jobId)
            ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        requireJobTarget(job, type, id)
        if (workflowRepository.cancelProcessingJob(jobId) != 1) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        workflowRepository.bumpResourceVersion(type, id, request.expectedVersion)
        return WorkflowExecution(details = mapOf("jobId" to jobId, "jobStatus" to "CANCELED"))
    }

    private fun resendNotification(
        actorAdminId: Long,
        type: AdminResourceType,
        id: Long,
        request: AdminWorkflowRequest,
    ): WorkflowExecution {
        val eventType = request.inboxEventType()
        val notificationId = notificationInboxRepository.create(
            eventType = eventType,
            targetType = type,
            targetId = id,
            safeSummary = eventType.safeSummary(type, id),
            correlationId = MDC.get(HttpLoggingFilter.TRACE_ID_KEY)?.takeIf(INBOX_CORRELATION_ID::matches),
            idempotencyKeyHash = inboxIdempotencyKeyHash(request.idempotencyKey),
            createdByAdminId = actorAdminId,
        )
        workflowRepository.bumpResourceVersion(type, id, request.expectedVersion)
        return WorkflowExecution(status = "COMPLETED", details = mapOf(
            "notificationId" to notificationId,
            "notificationType" to eventType.name,
            "inboxStatus" to "OPEN",
        ))
    }

    private fun createAiDraft(
        actorAdminId: Long,
        type: AdminResourceType,
        id: Long,
        request: AdminWorkflowRequest,
        previousJobId: Long?,
    ): WorkflowExecution {
        requireType(type, AdminResourceType.SELECTION)
        val result = workflowRepository.createAiSelectionDraft(
            id,
            actorAdminId,
            durableReason(request),
            request.expectedVersion,
            request.optionalInt("requestedCount"),
        )
        return WorkflowExecution(status = result.status, details = mapOf(
            "previousJobId" to previousJobId,
            "jobId" to result.jobId,
            "jobStatus" to result.status,
            "revisionId" to result.revisionId,
            "photoCount" to result.photoIds.size,
            "photoIds" to result.photoIds,
            "failureCode" to result.failureCode,
        ))
    }

    private fun albumTemplateDeletion(
        actorAdminId: Long,
        type: AdminResourceType,
        id: Long,
        request: AdminWorkflowRequest,
    ): WorkflowExecution {
        requireType(type, AdminResourceType.ALBUM)
        val templateId = request.long("templateId")
        val templateExpectedVersion = request.long("templateExpectedVersion")
        val result = if (request.action == AdminWorkflowAction.DELETE_ALBUM_TEMPLATE) {
            childTrashService.delete(
                actorAdminId = actorAdminId,
                type = AdminChildTrashType.ALBUM_TEMPLATE,
                resourceId = templateId,
                parentId = id,
                expectedParentVersion = request.expectedVersion,
                expectedChildVersion = templateExpectedVersion,
                reason = durableReason(request),
            )
        } else {
            childTrashService.restore(
                type = AdminChildTrashType.ALBUM_TEMPLATE,
                resourceId = templateId,
                parentId = id,
                expectedParentVersion = request.expectedVersion,
                expectedChildVersion = templateExpectedVersion,
            )
        }
        return WorkflowExecution(details = result.workflowDetails() + ("templateId" to templateId))
    }

    private fun retouchItemDeletion(
        actorAdminId: Long,
        type: AdminResourceType,
        id: Long,
        request: AdminWorkflowRequest,
    ): WorkflowExecution {
        requireType(type, AdminResourceType.RETOUCH_REQUEST)
        val itemId = request.long("retouchPhotoId")
        val itemExpectedVersion = request.long("retouchPhotoExpectedVersion")
        val result = if (request.action == AdminWorkflowAction.DELETE_RETOUCH_ITEM) {
            childTrashService.delete(
                actorAdminId = actorAdminId,
                type = AdminChildTrashType.RETOUCH_ITEM,
                resourceId = itemId,
                parentId = id,
                expectedParentVersion = request.expectedVersion,
                expectedChildVersion = itemExpectedVersion,
                reason = durableReason(request),
            )
        } else {
            childTrashService.restore(
                type = AdminChildTrashType.RETOUCH_ITEM,
                resourceId = itemId,
                parentId = id,
                expectedParentVersion = request.expectedVersion,
                expectedChildVersion = itemExpectedVersion,
            )
        }
        return WorkflowExecution(details = result.workflowDetails() + ("retouchPhotoId" to itemId))
    }

    private fun retryProcessingJob(
        actorAdminId: Long,
        type: AdminResourceType,
        id: Long,
        request: AdminWorkflowRequest,
        sourceAddress: String?,
        actionKey: String,
        requestHash: String,
    ): AdminWorkflowResponse {
        val jobId = request.long("jobId")
        return transactionTemplate.execute {
            val reservation = workflowRepository.reserveWorkflow(
                actionKey,
                request.idempotencyKey,
                requestHash,
                type,
                id,
            )
            reservation.requestHash?.let { existingHash ->
                if (existingHash != requestHash) throw AdminException(AdminErrorCode.IDEMPOTENCY_KEY_REUSED)
                if (reservation.status == "COMPLETED" && reservation.resultPayload != null) {
                    return@execute objectMapper.readValue(
                        reservation.resultPayload,
                        AdminWorkflowResponse::class.java,
                    ).copy(replayed = true)
                }
                throw AdminException(AdminErrorCode.REPROCESS_ALREADY_REQUESTED)
            }

            val currentResource = resourceRepository.find(type, id)
                ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
            if (currentResource.deleted || currentResource.version != request.expectedVersion) {
                throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
            }
            val before = snapshot(type, id, request.action)
            val job = workflowRepository.findProcessingJob(jobId)
                ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
            requireJobTarget(job, type, id)
            val queued = workflowRepository.queueProcessingJob(
                jobId = jobId,
                actorAdminId = actorAdminId,
                reason = durableReason(request),
            )
            workflowRepository.bumpResourceVersion(type, id, request.expectedVersion)
            val after = snapshot(type, id, request.action) + mapOf(
                "jobId" to jobId,
                "jobStatus" to "PENDING",
                "attemptCount" to queued.attemptCount,
            )
            auditService.recordMutation(
                action = AdminAuditAction.REPROCESS_REQUESTED,
                actorAdminId = actorAdminId,
                targetType = type.auditTargetType,
                targetId = id.toString(),
                targetLabel = resourceRepository.find(type, id)?.label,
                reason = durableReason(request),
                sourceAddress = sourceAddress,
                before = before,
                after = after,
            )
            val response = AdminWorkflowResponse(
                request.action,
                type,
                id,
                request.idempotencyKey,
                "PENDING",
                details = mapOf(
                    "jobId" to jobId,
                    "jobType" to queued.jobType,
                    "jobStatus" to "PENDING",
                    "attemptCount" to queued.attemptCount,
                ),
            )
            workflowRepository.completeWorkflow(
                actionKey,
                request.idempotencyKey,
                objectMapper.writeValueAsString(response),
            )
            response
        } ?: error("processing job retry transaction returned null")
    }

    private fun requireJobTarget(
        job: AdminWorkflowRepository.ProcessingJob,
        type: AdminResourceType,
        id: Long,
    ) {
        if (job.targetType != type || job.targetId != id) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
    }

    private fun snapshot(
        type: AdminResourceType,
        id: Long,
        action: AdminWorkflowAction,
    ): Map<String, Any?> {
        val resource = resourceRepository.find(type, id)
            ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        return linkedMapOf(
            "workflowAction" to action.name,
            "resource" to linkedMapOf(
                "type" to type.name,
                "id" to id,
                "version" to resource.version,
                "label" to resource.label,
                "deleted" to resource.deleted,
                "fields" to resource.fields,
            ),
            "facts" to contextRepository.findFacts(type, id),
            "sections" to contextRepository.findSections(type, id),
        )
    }

    private fun audit(
        actorAdminId: Long,
        type: AdminResourceType,
        id: Long,
        label: String?,
        request: AdminWorkflowRequest,
        sourceAddress: String?,
        before: Map<String, Any?>,
        after: Map<String, Any?>,
    ) {
        auditService.recordMutation(
            AdminAuditAction.RESOURCE_UPDATED,
            actorAdminId,
            type.auditTargetType,
            id.toString(),
            label,
            durableReason(request),
            sourceAddress,
            before,
            after,
        )
    }

    private fun AdminResourceResponse.auditSnapshot(): Map<String, Any?> =
        linkedMapOf(
            "type" to type.name,
            "id" to id,
            "version" to version,
            "label" to label,
            "deleted" to deleted,
        ) + fields

    private fun failReservation(actionKey: String, request: AdminWorkflowRequest, failureCode: String) {
        transactionTemplate.execute {
            workflowRepository.failWorkflow(actionKey, request.idempotencyKey, failureCode)
        }
    }

    private fun validateRequest(type: AdminResourceType, request: AdminWorkflowRequest) {
        if (!request.confirm || request.reason.isBlank() || request.reason.length > 500 || request.expectedVersion < 0) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        if (!IDEMPOTENCY_KEY.matches(request.idempotencyKey)) {
            throw AdminException(AdminErrorCode.INVALID_IDEMPOTENCY_KEY)
        }
        val action = request.action
        val allowedFields = action.requiredFields + action.optionalFields
        if (
            type !in action.allowedTargetTypes ||
            !request.fields.keys.containsAll(action.requiredFields) ||
            request.fields.keys.any { it !in allowedFields } ||
            (action.atLeastOneOf.isNotEmpty() && request.fields.keys.none { it in action.atLeastOneOf })
        ) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        validateRetouchArtifactReferences(request)
        if (request.action == AdminWorkflowAction.REPLACE_ALBUM_LAYOUT) {
            request.albumFolders()
        }
    }

    /**
     * 산출물 key 연결은 ISSUE/COMPLETE_RETOUCH_ARTIFACT_UPLOAD만 수행한다. UPDATE의 null은
     * 운영자가 이미 연결된 산출물을 명시적으로 제거할 때만 허용한다.
     */
    private fun validateRetouchArtifactReferences(request: AdminWorkflowRequest) {
        when (request.action) {
            AdminWorkflowAction.CREATE_RETOUCH_ITEM -> {
                if (request.fields["annotationKey"] != null) {
                    throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
                }
            }
            AdminWorkflowAction.UPDATE_RETOUCH_ITEM -> {
                val artifactFields = setOf("annotationKey", "resultKey", "resultContentType")
                if (artifactFields.any { name -> request.fields[name] != null }) {
                    throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
                }
                if (
                    request.fields.containsKey("resultKey") !=
                    request.fields.containsKey("resultContentType")
                ) {
                    throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
                }
            }
            else -> Unit
        }
    }

    private fun durableReason(request: AdminWorkflowRequest): String =
        auditSanitizer.canonicalOperatorReason(request.reason)

    private fun requestHash(type: AdminResourceType, id: Long, request: AdminWorkflowRequest): String {
        val canonical = linkedMapOf(
            "type" to type.name,
            "id" to id,
            "action" to request.action.name,
            "reason" to durableReason(request),
            "expectedVersion" to request.expectedVersion,
            "fields" to canonicalize(request.fields),
        )
        return MessageDigest.getInstance("SHA-256")
            .digest(objectMapper.writeValueAsString(canonical).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun inboxIdempotencyKeyHash(idempotencyKey: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("ADMIN_NOTIFICATION_INBOX:$idempotencyKey".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun canonicalize(value: Any?): Any? = when (value) {
        is Map<*, *> -> TreeMap<String, Any?>().apply {
            value.forEach { (key, nested) -> put(key.toString(), canonicalize(nested)) }
        }
        is Collection<*> -> value.map(::canonicalize)
        else -> value
    }

    private fun Map<String, Any?>.toAuditDetails(): Map<String, Any?> = mapValues { (key, value) ->
        if (key.endsWith("Url", ignoreCase = true)) "[REDACTED]" else value
    }

    /**
     * 초대·협업 토큰은 최초 성공 응답에서만 보인다. 사진 교체 URL도 저장하지 않되 pending
     * replacement의 storage key/content type으로 멱등 재생 때 새 URL을 transient하게 서명한다.
     * 따라서 DB의 멱등 결과와 감사/context 어느 쪽도 presigned URL 저장소가 되지 않는다.
     */
    private fun AdminWorkflowResponse.forIdempotentReplay(): AdminWorkflowResponse {
        if (action == AdminWorkflowAction.ISSUE_PHOTO_REPLACEMENT) {
            return copy(details = mapOf("replacementId" to details["replacementId"]))
        }
        if (action == AdminWorkflowAction.ISSUE_RETOUCH_ARTIFACT_UPLOAD) {
            return copy(details = details.filterKeys { key ->
                key in RETOUCH_UPLOAD_DURABLE_DETAIL_KEYS
            })
        }
        if (action !in ONE_TIME_REVEAL_ACTIONS) return this
        return copy(
            details = details
                .filterKeys { key -> !key.endsWith("Url", ignoreCase = true) }
                .plus("revealed" to false)
                .plus("oneTimeReveal" to true),
        )
    }

    private fun requireType(actual: AdminResourceType, expected: AdminResourceType) {
        if (actual != expected) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
    }

    private fun AdminWorkflowRequest.long(name: String): Long =
        (fields[name] as? Number)?.toLong()
            ?: fields[name]?.toString()?.toLongOrNull()
            ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)

    private fun AdminWorkflowRequest.optionalLong(name: String): Long? = fields[name]?.let { value ->
        (value as? Number)?.toLong() ?: value.toString().takeIf(String::isNotBlank)?.toLongOrNull()
            ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
    }

    private fun AdminWorkflowRequest.optionalInt(name: String): Int? = optionalLong(name)?.let { value ->
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        value.toInt()
    }

    private fun AdminWorkflowRequest.longs(name: String): List<Long> =
        (fields[name] as? Collection<*>)?.map { value ->
            (value as? Number)?.toLong() ?: value.toString().toLongOrNull()
                ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        } ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)

    private fun AdminWorkflowRequest.text(name: String, maxLength: Int): String =
        fields[name]?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= maxLength }
            ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)

    private fun AdminWorkflowRequest.optionalText(name: String, maxLength: Int): String? =
        fields[name]?.toString()?.trim()?.takeIf(String::isNotEmpty)?.also {
            if (it.length > maxLength) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }

    private fun AdminWorkflowRequest.safeFileName(name: String): String {
        val submitted = text(name, 255)
        val leaf = submitted.substringAfterLast('/').substringAfterLast('\\').trim()
        if (leaf.isEmpty() || leaf.length > 255 || leaf.any { it.code < 32 }) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        return leaf
    }

    private fun AdminWorkflowRequest.retouchArtifactType(): AdminRetouchArtifactType =
        runCatching { AdminRetouchArtifactType.valueOf(text("artifactType", 20).uppercase()) }
            .getOrElse { throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS) }

    private fun AdminWorkflowResponse.detailLong(name: String): Long =
        (details[name] as? Number)?.toLong()
            ?: details[name]?.toString()?.toLongOrNull()
            ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)

    private fun AdminWorkflowRequest.inboxEventType(): AdminInboxEventType =
        runCatching { AdminInboxEventType.valueOf(text("notificationType", 80)) }
            .getOrElse { throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS) }

    private fun validateRetouchArtifactContentType(
        artifactType: AdminRetouchArtifactType,
        contentType: String,
    ) {
        val valid = when (artifactType) {
            AdminRetouchArtifactType.ANNOTATION -> contentType == "image/png"
            AdminRetouchArtifactType.RESULT -> contentType in RETOUCH_RESULT_CONTENT_TYPES
        }
        if (!valid) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
    }

    private fun buildRetouchArtifactKey(
        galleryId: Long,
        roundNo: Int,
        retouchPhotoId: Long,
        artifactType: AdminRetouchArtifactType,
        contentType: String,
    ): String {
        val directory = when (artifactType) {
            AdminRetouchArtifactType.ANNOTATION -> "annotations"
            AdminRetouchArtifactType.RESULT -> "results"
        }
        val extension = RETOUCH_CONTENT_TYPE_EXTENSIONS[contentType]
            ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        return "galleries/$galleryId/retouch/$directory/$roundNo/$retouchPhotoId/${tokenGenerator.generate()}.$extension"
    }

    private fun AdminWorkflowRequest.boolean(name: String): Boolean = when (val value = fields[name]) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull() ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        else -> throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
    }

    private fun AdminWorkflowRequest.optionalOffsetDateTime(name: String): OffsetDateTime? =
        fields[name]?.let { value ->
            runCatching { OffsetDateTime.parse(value.toString()) }
                .getOrElse { throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS) }
        }

    private fun AdminWorkflowRequest.map(name: String): Map<String, Any?> =
        (fields[name] as? Map<*, *>)?.entries?.associate { (key, value) -> key.toString() to value }
            ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)

    private fun AdminWorkflowRequest.optionalMap(name: String): Map<String, Any?>? =
        if (!fields.containsKey(name) || fields[name] == null) null else map(name)

    private fun AdminWorkflowRequest.retouchItemPatch(): Map<String, Any?> = buildMap {
        if (fields.containsKey("requestText")) put("requestText", optionalText("requestText", 2000))
        if (fields.containsKey("annotationKey")) put("annotationKey", optionalText("annotationKey", 500))
        if (fields.containsKey("resultKey")) put("resultKey", optionalText("resultKey", 500))
        if (fields.containsKey("resultContentType")) {
            put("resultContentType", optionalText("resultContentType", 100))
        }
        if (fields.containsKey("structuredAiMetadata")) {
            put("structuredAiMetadata", optionalMap("structuredAiMetadata"))
        }
    }

    private fun AdminWorkflowRequest.albumFolders(): List<AdminWorkflowRepository.AlbumFolder> {
        val rawFolders = fields["folders"] as? Collection<*>
            ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        if (rawFolders.size > MAX_ALBUM_FOLDERS) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val photoIds = mutableSetOf<Long>()
        var itemCount = 0
        return rawFolders.map { rawFolder ->
            val folder = rawFolder as? Map<*, *> ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            val name = folder["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 100 }
                ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            val rawItems = when (val value = folder["items"]) {
                null -> if (folder.containsKey("items")) {
                    throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
                } else {
                    emptyList<Any?>()
                }
                is Collection<*> -> value
                else -> throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            }
            itemCount += rawItems.size
            if (itemCount > MAX_ALBUM_ITEMS) {
                throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            }
            val sortOrders = mutableSetOf<Int>()
            val items = rawItems.mapIndexed { index, rawItem ->
                val item = rawItem as? Map<*, *> ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
                val photoId = strictPositiveLong(item["photoId"])
                if (!photoIds.add(photoId)) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
                val sortOrder = if (item.containsKey("sortOrder")) {
                    strictNonNegativeInt(item["sortOrder"])
                } else {
                    index
                }
                if (!sortOrders.add(sortOrder)) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
                AdminWorkflowRepository.AlbumItem(
                    photoId = photoId,
                    sortOrder = sortOrder,
                    crop = normalizedCrop(item["crop"]),
                )
            }
            AdminWorkflowRepository.AlbumFolder(name, items)
        }
    }

    private fun strictPositiveLong(value: Any?): Long {
        val integer = exactInteger(value)
        if (integer <= java.math.BigInteger.ZERO || integer > LONG_MAX) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        return integer.toLong()
    }

    private fun strictNonNegativeInt(value: Any?): Int {
        val integer = exactInteger(value)
        if (integer < java.math.BigInteger.ZERO || integer > INT_MAX) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        return integer.toInt()
    }

    private fun exactInteger(value: Any?): java.math.BigInteger {
        val number = value as? Number ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        return runCatching { number.toString().toBigDecimal().toBigIntegerExact() }
            .getOrElse { throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS) }
    }

    private fun normalizedCrop(value: Any?): Map<String, Any?>? {
        if (value == null) return null
        val raw = value as? Map<*, *> ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        if (raw.keys.any { it !is String } || raw.keys != NORMALIZED_CROP_KEYS) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val values = NORMALIZED_CROP_KEYS_IN_ORDER.map { name ->
            (raw[name] as? Number)?.toDouble()?.takeIf { candidate -> candidate.isFinite() }
                ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val (x, y, width, height) = values
        if (
            x < 0 || y < 0 || width <= 0 || height <= 0 ||
            x + width > 1 + NORMALIZED_CROP_EPSILON ||
            y + height > 1 + NORMALIZED_CROP_EPSILON
        ) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        return NORMALIZED_CROP_KEYS_IN_ORDER.zip(values).toMap(LinkedHashMap())
    }

    private sealed interface PreparedExternalWork {
        data object None : PreparedExternalWork
        data class Upload(
            val storageKey: String,
            val fileName: String,
            val contentType: String,
            val url: String,
            val expiresAt: java.time.Instant,
        ) : PreparedExternalWork
        data class VerifiedReplacement(val replacementId: Long) : PreparedExternalWork
        data class RetouchArtifactUpload(
            val retouchPhotoId: Long,
            val artifactType: AdminRetouchArtifactType,
            val storageKey: String,
            val fileName: String,
            val contentType: String,
            val url: String,
            val expiresAt: java.time.Instant,
        ) : PreparedExternalWork
        data class VerifiedRetouchArtifact(
            val uploadId: Long,
            val retouchPhotoId: Long,
        ) : PreparedExternalWork
    }

    private data class WorkflowExecution(
        val status: String = "COMPLETED",
        val details: Map<String, Any?> = emptyMap(),
    )

    companion object {
        private val ALLOWED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")
        private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9._:-]{8,128}$")
        private val INBOX_CORRELATION_ID = Regex("^[0-9a-f]{16}$")
        private const val DEFAULT_COLLAB_TTL_SECONDS = 7L * 24 * 60 * 60
        private const val MAX_COLLAB_TTL_SECONDS = 90L * 24 * 60 * 60
        private val CHILD_RESTORE_ACTIONS = setOf(
            AdminWorkflowAction.RESTORE_COLLAB_COMMENT,
            AdminWorkflowAction.RESTORE_COLLAB_LIKE,
            AdminWorkflowAction.RESTORE_ALBUM_TEMPLATE,
            AdminWorkflowAction.RESTORE_RETOUCH_ITEM,
        )
        private val ONE_TIME_REVEAL_ACTIONS = setOf(
            AdminWorkflowAction.REISSUE_GALLERY_INVITE,
            AdminWorkflowAction.REISSUE_COLLAB_LINK,
            AdminWorkflowAction.ISSUE_PHOTO_REPLACEMENT,
        )
        private val RETOUCH_RESULT_CONTENT_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/heic",
            "image/heif",
        )
        private val RETOUCH_CONTENT_TYPE_EXTENSIONS = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
            "image/heic" to "heic",
            "image/heif" to "heif",
        )
        private val RETOUCH_UPLOAD_DURABLE_DETAIL_KEYS = setOf(
            "uploadId",
            "retouchPhotoId",
            "artifactType",
            "roundVersion",
            "retouchPhotoVersion",
        )
        private const val MAX_ALBUM_FOLDERS = 50
        private const val MAX_ALBUM_ITEMS = 1_000
        private const val NORMALIZED_CROP_EPSILON = 0.000_001
        private val NORMALIZED_CROP_KEYS_IN_ORDER = listOf("x", "y", "width", "height")
        private val NORMALIZED_CROP_KEYS = NORMALIZED_CROP_KEYS_IN_ORDER.toSet()
        private val INT_MAX = java.math.BigInteger.valueOf(Int.MAX_VALUE.toLong())
        private val LONG_MAX = java.math.BigInteger.valueOf(Long.MAX_VALUE)
    }
}

package com.soma.wes.admin.impersonation.service

import com.soma.wes.admin.audit.support.AdminAuditSanitizer
import com.soma.wes.admin.impersonation.dto.AdminImpersonationViewResponse
import com.soma.wes.admin.impersonation.repository.AdminImpersonationViewRepository
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.category.service.CategoryService
import com.soma.wes.collab.dto.response.CollabSessionResponse
import com.soma.wes.collab.service.CollabSessionQueryService
import com.soma.wes.gallery.service.GalleryService
import com.soma.wes.global.exception.BusinessException
import com.soma.wes.photo.dto.response.PhotoResponse
import com.soma.wes.photo.service.PhotoService
import com.soma.wes.retouch.service.RetouchService
import com.soma.wes.selection.service.PhotoSelectionService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** 실제 사용자 서비스의 인가 관문을 재사용해 관리자 대리보기 read model을 구성한다. */
@Service
class AdminImpersonationReadModelService(
    private val baseRepository: AdminImpersonationViewRepository,
    private val galleryService: GalleryService,
    private val photoService: PhotoService,
    private val selectionService: PhotoSelectionService,
    private val collabSessionQueryService: CollabSessionQueryService,
    private val categoryService: CategoryService,
    private val retouchService: RetouchService,
    private val sanitizer: AdminAuditSanitizer,
    transactionManager: PlatformTransactionManager,
) {

    /** 금지된 section의 조회 실패가 대리보기 세션 생성과 감사 트랜잭션까지 rollback-only로 만들지 않게 분리한다. */
    private val sectionRead = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isReadOnly = true
    }

    @Transactional(readOnly = true)
    fun view(
        type: AdminResourceType,
        targetId: Long,
        viewer: AdminImpersonationViewRepository.Viewer,
    ): AdminImpersonationViewResponse {
        val base = baseRepository.view(type, targetId, viewer)
        val galleries = galleryService.findAllVisibleTo(viewer.userId).filter { gallery ->
            when (type) {
                AdminResourceType.STUDIO -> gallery.workspaceId == targetId
                AdminResourceType.GALLERY -> gallery.id == targetId
                else -> true
            }
        }

        val photos = mutableListOf<Map<String, Any?>>()
        val selections = mutableListOf<Map<String, Any?>>()
        val collaborations = mutableListOf<Map<String, Any?>>()
        val comments = mutableListOf<Map<String, Any?>>()
        val categories = mutableListOf<Map<String, Any?>>()
        val retouch = mutableListOf<Map<String, Any?>>()
        var photoTotal = 0L

        galleries.forEach { gallery ->
            val photoPage = readable {
                photoService.list(gallery.id, viewer.userId, null, null, 0, SECTION_LIMIT)
            }
            photoTotal += photoPage?.totalCount ?: 0
            photoPage?.contents.orEmpty().mapTo(photos) { safePhoto(gallery.id, it) }

            readable { selectionService.get(gallery.id, viewer.userId) }?.let { selection ->
                selections += linkedMapOf(
                    "galleryId" to gallery.id,
                    "status" to selection.status.name,
                    "maxSelectablePhotoCount" to selection.maxSelectablePhotoCount,
                    "selectedCount" to selection.selectedCount,
                    "remainingCount" to selection.remainingCount,
                    "submittedAt" to selection.submittedAt,
                    "photos" to selection.photos.map { selected ->
                        linkedMapOf(
                            "photo" to safePhoto(gallery.id, selected.photo),
                            "retouchPhotoId" to selected.retouchPhotoId,
                        )
                    },
                )
            }

            readable { collabSessionQueryService.list(gallery.id, viewer.userId) }.orEmpty()
                .mapTo(collaborations, ::safeCollaboration)
            readable { collabSessionQueryService.listViewerComments(gallery.id, viewer.userId) }.orEmpty()
                .mapTo(comments) { comment ->
                    linkedMapOf(
                        "galleryId" to gallery.id,
                        "sessionId" to comment.sessionId,
                        "commentId" to comment.commentId,
                        "photoId" to comment.photoId,
                        "content" to sanitizer.sanitizeText(comment.content),
                        "createdAt" to comment.createdAt,
                    )
                }

            readable { categoryService.list(gallery.id, viewer.userId) }.orEmpty().mapTo(categories) { concept ->
                linkedMapOf(
                    "galleryId" to gallery.id,
                    "conceptFolderId" to concept.id,
                    "name" to sanitizer.sanitizeText(concept.name),
                    "createdSource" to concept.createdSource.name,
                    "analysisJobId" to concept.analysisJobId,
                    "details" to concept.details.map { detail ->
                        linkedMapOf(
                            "detailFolderId" to detail.id,
                            "name" to sanitizer.sanitizeText(detail.name),
                            "category" to detail.category?.name,
                            "needsReview" to detail.needsReview,
                            "photoCount" to detail.photoIds.size,
                        )
                    },
                )
            }

            readable { retouchService.get(gallery.id, viewer.userId) }?.let { overview ->
                retouch += linkedMapOf(
                    "galleryId" to gallery.id,
                    "maxRetouchRoundCount" to overview.maxRetouchRoundCount,
                    "remainingRoundCount" to overview.remainingRoundCount,
                    "rounds" to overview.rounds.map { round ->
                        linkedMapOf(
                            "roundNo" to round.roundNo,
                            "status" to round.status.name,
                            "requestedAt" to round.requestedAt,
                            "completedAt" to round.completedAt,
                            "photoCount" to round.photoCount,
                        )
                    },
                    "currentRound" to overview.currentRound?.let { round ->
                        linkedMapOf(
                            "roundNo" to round.roundNo,
                            "status" to round.status.name,
                            "requestedAt" to round.requestedAt,
                            "completedAt" to round.completedAt,
                            "photos" to round.photos.map { item ->
                                linkedMapOf(
                                    "retouchPhotoId" to item.retouchPhotoId,
                                    "photo" to safePhoto(gallery.id, item.photo),
                                    "requestText" to sanitizer.sanitizeText(item.requestText),
                                    "hasResult" to item.hasResult,
                                )
                            },
                        )
                    },
                )
            }
        }

        val sections = linkedMapOf(
            "photos" to photos,
            "selections" to selections,
            "collaborations" to collaborations,
            "comments" to comments,
            "categories" to categories,
            "retouch" to retouch,
        )
        val counts = sections.mapValues { (_, rows) -> rows.size }.toMutableMap()
        counts["photos"] = photoTotal.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val visibleGalleryIds = galleries.mapTo(mutableSetOf()) { it.id }
        return base.copy(
            // base SQL의 요약도 실제 GalleryService 목록 정책을 통과한 갤러리만 노출한다.
            galleries = base.galleries.filter { it.id in visibleGalleryIds },
            sections = sections,
            sectionCounts = counts,
            sectionFields = sections.mapValues { (_, rows) -> rows.flatMap { it.keys }.distinct() },
        )
    }

    /** signed URL, storage key, original filename/content type을 의도적으로 제외한다. */
    private fun safePhoto(galleryId: Long, photo: PhotoResponse): Map<String, Any?> = linkedMapOf(
        "galleryId" to galleryId,
        "photoId" to photo.photoId,
        "status" to photo.status.name,
        "displayOrder" to photo.displayOrder,
        "createdAt" to photo.createdAt,
        "previewReady" to photo.previewReady,
        "score" to photo.score,
    )

    /** collabUrl에는 접근 token이 들어가므로 반환하지 않는다. */
    private fun safeCollaboration(session: CollabSessionResponse): Map<String, Any?> = linkedMapOf(
        "galleryId" to session.galleryId,
        "sessionId" to session.sessionId,
        "name" to sanitizer.sanitizeText(session.name),
        "revoked" to session.revoked,
        "revokedAt" to session.revokedAt,
        "photoCount" to session.photoCount,
        "createdAt" to session.createdAt,
    )

    private fun <T> readable(block: () -> T): T? = try {
        sectionRead.execute { block() }
    } catch (_: BusinessException) {
        // 사용자 API가 금지한 section은 관리자 대리보기에서도 우회 노출하지 않는다.
        null
    }

    companion object {
        private const val SECTION_LIMIT = 200
    }
}

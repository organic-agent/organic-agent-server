package com.soma.wes.gallery.service

import com.soma.wes.billing.support.GalleryPasses
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.dto.request.CreatePersonalGalleryRequest
import com.soma.wes.gallery.dto.request.UpdatePersonalGalleryRequest
import com.soma.wes.gallery.dto.response.GalleryResponse
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.selection.domain.PhotoSelection
import com.soma.wes.selection.repository.PhotoSelectionRepository
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.user.repository.requireWithLockById
import com.soma.wes.workspace.service.WorkspaceService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@Service
class PersonalGalleryService(
    private val galleryPasses: GalleryPasses,
    private val userRepository: UserRepository,
    private val workspaceService: WorkspaceService,
    private val galleryRepository: GalleryRepository,
    private val selectionRepository: PhotoSelectionRepository,
    private val accessPolicy: GalleryAccessPolicy,
    private val clock: Clock,
) {
    /** 무료 1회·프로 쿠폰 1회를 사용자 잠금 안에서 소비한다. 기간은 갤러리 생성 시점부터 시작한다. */
    @Transactional
    fun create(userId: Long, request: CreatePersonalGalleryRequest): GalleryResponse {
        val user = userRepository.requireWithLockById(userId)

        val now = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)
        val pass = galleryPasses.prepare(
            userId = userId, planId = request.planId, couponId = request.couponId,
            checkoutId = request.checkoutId, at = now,
        )
        val deadline = request.selectionDeadline ?: pass.expiresAt
        if (deadline.isAfter(pass.expiresAt)) throw GalleryException(GalleryErrorCode.INVALID_SELECTION_DEADLINE)
        val workspace = workspaceService.ensurePersonalWorkspace(user)
        val gallery = Gallery.create(
            workspaceId = workspace.requiredId, createdByUserId = userId, title = request.title,
            selectionDeadline = deadline, maxSelectablePhotoCount = request.maxSelectablePhotoCount,
            maxRetouchRoundCount = null, shootType = request.shootType, at = now,
        )
        gallery.status = GalleryStatus.OPEN
        gallery.planExpiresAt = pass.expiresAt
        gallery.planMaxPhotoCount = pass.maxPhotoCount
        gallery.planType = pass.plan
        galleryRepository.save(gallery)
        selectionRepository.save(PhotoSelection(galleryId = gallery.requiredId))
        galleryPasses.consume(userId = userId, galleryId = gallery.requiredId, pass = pass, at = now)
        return GalleryResponse.from(gallery)
    }

    @Transactional
    fun update(galleryId: Long, userId: Long, request: UpdatePersonalGalleryRequest): GalleryResponse {
        accessPolicy.requireManager(galleryId, userId)
        if (!accessPolicy.isPersonalGallery(galleryId)) throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        val gallery = galleryRepository.requireWithLockById(galleryId)
        val now = ZonedDateTime.now(clock)
        gallery.requireWritable(now)
        if (request.selectionDeadline != null && gallery.planExpiresAt?.let { request.selectionDeadline.isAfter(it) } == true) {
            throw GalleryException(GalleryErrorCode.INVALID_SELECTION_DEADLINE)
        }
        gallery.rename(request.title)
        gallery.changeSelectionDeadline(request.selectionDeadline, now)
        gallery.changeMaxSelectablePhotoCount(request.maxSelectablePhotoCount)
        return GalleryResponse.from(gallery)
    }
}

package com.soma.wes.gallery.service

import com.soma.wes.billing.exception.BillingErrorCode
import com.soma.wes.billing.exception.BillingException
import com.soma.wes.billing.repository.TestCheckoutRepository
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
import com.soma.wes.user.repository.requireById
import com.soma.wes.workspace.service.WorkspaceService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

@Service
class PersonalGalleryService(
    private val checkoutRepository: TestCheckoutRepository,
    private val userRepository: UserRepository,
    private val workspaceService: WorkspaceService,
    private val galleryRepository: GalleryRepository,
    private val selectionRepository: PhotoSelectionRepository,
    private val accessPolicy: GalleryAccessPolicy,
    private val clock: Clock,
) {
    /** 결제 주인과 사용 여부를 같은 잠금 안에서 검증해 갤러리 중복 개설을 막는다. */
    @Transactional
    fun create(userId: Long, request: CreatePersonalGalleryRequest): GalleryResponse {
        val user = userRepository.requireById(userId)
        val checkout = checkoutRepository.findWithLockByIdAndUserId(request.checkoutId, userId)
            ?: throw BillingException(BillingErrorCode.CHECKOUT_NOT_FOUND)
        if (checkout.consumedAt != null) throw BillingException(BillingErrorCode.CHECKOUT_ALREADY_USED)
        val now = ZonedDateTime.now(clock)
        if (!checkout.expiresAt.isAfter(now)) throw BillingException(BillingErrorCode.CHECKOUT_EXPIRED)
        val deadline = request.selectionDeadline ?: checkout.expiresAt
        if (deadline.isAfter(checkout.expiresAt)) throw GalleryException(GalleryErrorCode.INVALID_SELECTION_DEADLINE)
        val workspace = workspaceService.ensurePersonalWorkspace(user)
        val gallery = Gallery.create(
            workspaceId = workspace.requiredId, createdByUserId = userId, title = request.title,
            selectionDeadline = deadline, maxSelectablePhotoCount = request.maxSelectablePhotoCount,
            maxRetouchRoundCount = null, shootType = request.shootType, at = now,
        )
        gallery.status = GalleryStatus.OPEN
        gallery.planExpiresAt = checkout.expiresAt
        gallery.planMaxPhotoCount = checkout.maxPhotoCount
        galleryRepository.save(gallery)
        selectionRepository.save(PhotoSelection(galleryId = gallery.requiredId))
        checkout.galleryId = gallery.requiredId
        checkout.consumedAt = now
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

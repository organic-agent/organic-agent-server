package com.soma.wes.gallery.service

import com.soma.wes.analysis.support.ConceptAssignmentLoader
import com.soma.wes.auth.config.ReviewLoginProperties
import com.soma.wes.folder.support.AiFolderMaterializer
import com.soma.wes.gallery.dto.response.GalleryResponse
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryProgressResetRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.selection.domain.PhotoSelection
import com.soma.wes.selection.repository.PhotoSelectionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 심사용 계정의 개인 갤러리를 "사진을 올리고 AI 분석이 끝난 직후"로 되돌린다. 심사위원이 앞 사람의 흔적 없이 처음부터 써 보게 하려는 것이다.
 *
 * 지우는 것: 셀렉·별점·메모·댓글·거르기·추천·보정 요청·앨범·협업 링크·폴더 수정·활동 기록. 남기는 것: 사진·AI 분석·파트너 초대.
 * 폴더는 지운 뒤 최신 AI 분석으로 다시 만든다 — 분석이 아직 없으면 폴더 없이 두고, 분석이 끝날 때 파이프라인이 만든다.
 *
 * 되돌릴 수 없어서 심사용 계정([ReviewLoginProperties.userId])에게만 연다. 일반 사용자는 403이다.
 */
@Service
class ReviewGalleryResetService(
    private val reviewLoginProperties: ReviewLoginProperties,
    private val accessPolicy: GalleryAccessPolicy,
    private val galleryRepository: GalleryRepository,
    private val resetRepository: GalleryProgressResetRepository,
    private val selectionRepository: PhotoSelectionRepository,
    private val conceptAssignmentLoader: ConceptAssignmentLoader,
    private val aiFolderMaterializer: AiFolderMaterializer,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun reset(galleryId: Long, userId: Long): GalleryResponse {
        if (!reviewLoginProperties.isReviewAccount(userId)) {
            throw GalleryException(GalleryErrorCode.REVIEW_RESET_NOT_ALLOWED)
        }
        accessPolicy.requirePersonalParticipant(galleryId, userId)
        val gallery = galleryRepository.requireWithLockById(galleryId)

        resetRepository.deleteProgress(galleryId, ZonedDateTime.now(clock))
        selectionRepository.save(PhotoSelection(galleryId = galleryId))
        gallery.resetProgress()

        if (conceptAssignmentLoader.loadLatest(galleryId) != null) {
            aiFolderMaterializer.materialize(galleryId)
        }
        log.info("심사용 갤러리를 초기화했습니다 (galleryId={}, userId={})", galleryId, userId)

        return GalleryResponse.from(gallery)
    }
}

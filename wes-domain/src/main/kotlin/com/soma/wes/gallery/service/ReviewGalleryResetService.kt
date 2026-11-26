package com.soma.wes.gallery.service

import com.soma.wes.auth.config.ReviewLoginProperties
import com.soma.wes.gallery.dto.response.GalleryResponse
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.gallery.support.GalleryProgressResetter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 심사용 계정의 개인 갤러리를 "사진을 올리고 AI 분석이 끝난 직후"로 되돌린다. 심사위원이 앞 사람의 흔적 없이 처음부터 써 보게 하려는 것이다.
 *
 * 무엇을 지우고 남기는지는 [GalleryProgressResetter]가 정한다. 파트너 초대는 남긴다 — 심사위원이 함께 고르기를 써 볼 수 있어야 한다.
 *
 * 되돌릴 수 없어서 심사용 계정([ReviewLoginProperties.userId])에게만 연다. 일반 사용자는 403이다.
 */
@Service
class ReviewGalleryResetService(
    private val reviewLoginProperties: ReviewLoginProperties,
    private val accessPolicy: GalleryAccessPolicy,
    private val progressResetter: GalleryProgressResetter,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun reset(galleryId: Long, userId: Long): GalleryResponse {
        if (!reviewLoginProperties.isReviewAccount(userId)) {
            throw GalleryException(GalleryErrorCode.REVIEW_RESET_NOT_ALLOWED)
        }
        accessPolicy.requirePersonalParticipant(galleryId, userId)

        val gallery = progressResetter.reset(galleryId, withMembership = false)
        log.info("심사용 갤러리를 초기화했습니다 (galleryId={}, userId={})", galleryId, userId)

        return GalleryResponse.from(gallery)
    }
}

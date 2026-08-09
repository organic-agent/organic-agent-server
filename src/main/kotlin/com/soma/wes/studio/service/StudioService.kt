package com.soma.wes.studio.service

import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.dto.request.CreateStudioRequest
import com.soma.wes.studio.dto.request.UpdateStudioRequest
import com.soma.wes.studio.dto.response.GalleryUrlAvailabilityResponse
import com.soma.wes.studio.dto.response.StudioResponse
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.studio.support.StudioWriteAdmission
import com.soma.wes.user.domain.UserType
import com.soma.wes.user.exception.UserErrorCode
import com.soma.wes.user.exception.UserException
import com.soma.wes.user.repository.UserRepository
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
class StudioService(
    private val studioRepository: StudioRepository,
    private val userRepository: UserRepository,
    private val studioWriteAdmission: StudioWriteAdmission,
) {

    @Transactional
    fun create(userId: Long, request: CreateStudioRequest): StudioResponse {
        val normalizedGalleryUrl = Studio.normalizeGalleryUrl(request.galleryUrl)
        val user = userRepository.findById(userId)
            .orElseThrow { UserException(UserErrorCode.USER_NOT_FOUND) }

        if (studioRepository.existsByUserId(userId)) {
            throw StudioException(StudioErrorCode.STUDIO_ALREADY_EXISTS)
        }

        // 현재 기본적인 회원가입 플로우는 사진작가에게만 부여, 신혼부부는 token기반 회원가입 flow를 타야한다.
        user.selectType(UserType.PHOTOGRAPHER)

        if (studioRepository.existsByGalleryUrl(normalizedGalleryUrl)) {
            throw StudioException(StudioErrorCode.GALLERY_URL_DUPLICATED)
        }

        val studio = saveAndFlush(
            Studio(
                userId = userId,
                name = request.name,
                galleryUrl = normalizedGalleryUrl,
                inflowChannel = request.inflowChannel,
            ),
        )
        return StudioResponse.from(studio)
    }

    @Transactional(readOnly = true)
    fun getMyStudio(userId: Long): StudioResponse = StudioResponse.from(findMyStudio(userId))

    @Transactional
    fun updateMyStudio(userId: Long, request: UpdateStudioRequest): StudioResponse {
        val studio = studioWriteAdmission.lockWritableByUserId(userId)
        val normalizedGalleryUrl = Studio.normalizeGalleryUrl(request.galleryUrl)

        if (normalizedGalleryUrl != studio.galleryUrl && studioRepository.existsByGalleryUrl(normalizedGalleryUrl)) {
            throw StudioException(StudioErrorCode.GALLERY_URL_DUPLICATED)
        }

        studio.update(request.name, normalizedGalleryUrl)
        return StudioResponse.from(saveAndFlush(studio))
    }

    private fun saveAndFlush(studio: Studio): Studio =
        try {
            studioRepository.saveAndFlush(studio)
        } catch (exception: DataIntegrityViolationException) {
            val constraintName = generateSequence<Throwable>(exception) { it.cause }
                .filterIsInstance<ConstraintViolationException>()
                .mapNotNull { it.constraintName }
                .firstOrNull()

            when (constraintName) {
                "uk_studios_user_id" -> throw StudioException(StudioErrorCode.STUDIO_ALREADY_EXISTS)
                "uk_studios_gallery_url" -> throw StudioException(StudioErrorCode.GALLERY_URL_DUPLICATED)
                else -> throw exception
            }
        }

    private fun findMyStudio(userId: Long): Studio =
        studioRepository.findByUserId(userId)
            ?: throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)

    @Transactional(readOnly = true)
    fun checkGalleryUrl(galleryUrl: String): GalleryUrlAvailabilityResponse {
        val normalizedGalleryUrl = Studio.normalizeGalleryUrl(galleryUrl)
        if (!Studio.isValidGalleryUrl(normalizedGalleryUrl)) {
            throw StudioException(StudioErrorCode.INVALID_GALLERY_URL)
        }

        return GalleryUrlAvailabilityResponse(
            galleryUrl = normalizedGalleryUrl,
            available = !studioRepository.existsByGalleryUrl(normalizedGalleryUrl),
        )
    }
}

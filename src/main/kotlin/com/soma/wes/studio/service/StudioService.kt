package com.soma.wes.studio.service

import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.dto.request.CreateStudioRequest
import com.soma.wes.studio.dto.request.UpdateStudioRequest
import com.soma.wes.studio.dto.response.GalleryUrlAvailabilityResponse
import com.soma.wes.studio.dto.response.StudioResponse
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.domain.UserType
import com.soma.wes.user.exception.UserErrorCode
import com.soma.wes.user.exception.UserException
import com.soma.wes.user.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
@Transactional(readOnly = true)
class StudioService(
    private val studioRepository: StudioRepository,
    private val userRepository: UserRepository,
) {

    @Transactional
    fun create(userId: Long, request: CreateStudioRequest): StudioResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { UserException(UserErrorCode.USER_NOT_FOUND) }

        if (studioRepository.existsByUserId(userId)) {
            throw StudioException(StudioErrorCode.STUDIO_ALREADY_EXISTS)
        }

        // 현재 기본적인 회원가입 플로우는 사진작가에게만 부여, 신혼부부는 token기반 회원가입 flow를 타야한다.
        user.selectType(UserType.PHOTOGRAPHER)

        if (studioRepository.existsByGalleryUrl(request.galleryUrl)) {
            throw StudioException(StudioErrorCode.GALLERY_URL_DUPLICATED)
        }

        val studio = studioRepository.save(
            Studio(
                userId = userId,
                name = request.name,
                galleryUrl = request.galleryUrl,
                inflowChannel = request.inflowChannel,
            ),
        )
        return StudioResponse.from(studio)
    }

    fun getMyStudio(userId: Long): StudioResponse = StudioResponse.from(findMyStudio(userId))

    @Transactional
    fun updateMyStudio(userId: Long, request: UpdateStudioRequest): StudioResponse {
        val studio = findMyStudio(userId)

        if (request.galleryUrl != studio.galleryUrl && studioRepository.existsByGalleryUrl(request.galleryUrl)) {
            throw StudioException(StudioErrorCode.GALLERY_URL_DUPLICATED)
        }

        studio.update(request.name, request.galleryUrl)
        return StudioResponse.from(studio)
    }

    private fun findMyStudio(userId: Long): Studio =
        studioRepository.findByUserId(userId)
            ?: throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)

    fun checkGalleryUrl(galleryUrl: String): GalleryUrlAvailabilityResponse {
        if (!Studio.isValidGalleryUrl(galleryUrl)) {
            throw StudioException(StudioErrorCode.INVALID_GALLERY_URL)
        }

        return GalleryUrlAvailabilityResponse(
            galleryUrl = galleryUrl,
            available = !studioRepository.existsByGalleryUrl(galleryUrl),
        )
    }
}

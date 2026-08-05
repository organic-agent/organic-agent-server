package com.soma.wes.studio.service

import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.domain.UserType
import com.soma.wes.user.exception.UserErrorCode
import com.soma.wes.user.exception.UserException
import com.soma.wes.user.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 사진작가 온보딩.
 *
 * 스튜디오를 만드는 것이 곧 "나는 작가다"라는 선언이다. 종류만 정하는 API를 따로 두지 않는
 * 이유는 그것을 고르는 화면이 없기 때문이다 — 랜딩페이지로 가입한 사람은 곧바로 스튜디오
 * 생성으로 들어오고, 예비 부부는 초대 링크로만 들어온다. 종류를 별도로 받으면 실제로 한 일과
 * 어긋난 값(작가라고 해놓고 스튜디오는 없는 상태)이 남을 수 있다.
 */
@Service
@Transactional(readOnly = true)
class StudioService(
    private val studioRepository: StudioRepository,
    private val userRepository: UserRepository,
) {

    @Transactional
    fun create(userId: Long, name: String, galleryUrl: String, inflowChannel: String?): Studio {
        val user = userRepository.findById(userId)
            .orElseThrow { UserException(UserErrorCode.USER_NOT_FOUND) }

        // 종류 확정보다 먼저 본다. 이미 스튜디오가 있는 사용자는 종류도 이미 PHOTOGRAPHER라,
        // 순서를 바꾸면 "이미 스튜디오가 있다" 대신 "이미 종류가 정해졌다"가 나가 원인을 가린다.
        if (studioRepository.existsByUserId(userId)) {
            throw StudioException(StudioErrorCode.STUDIO_ALREADY_EXISTS)
        }

        // 초대로 먼저 들어와 CLIENT가 된 사용자는 여기서 막힌다(USER_409_1).
        // 종류를 바꾸면 이미 수락한 초대나 만든 갤러리의 주인이 어긋난다.
        user.selectType(UserType.PHOTOGRAPHER)

        if (studioRepository.existsByGalleryUrl(galleryUrl)) {
            throw StudioException(StudioErrorCode.GALLERY_URL_DUPLICATED)
        }

        // 주소 형식·예약어 검증은 생성자가 한다(INVALID_GALLERY_URL).
        return studioRepository.save(
            Studio(userId = userId, name = name, galleryUrl = galleryUrl, inflowChannel = inflowChannel),
        )
    }

    fun getMine(userId: Long): Studio =
        studioRepository.findByUserId(userId)
            ?: throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)

    @Transactional
    fun updateMine(userId: Long, name: String, galleryUrl: String): Studio {
        val studio = getMine(userId)

        // 주소를 그대로 두고 이름만 바꾸는 요청이 자기 주소에 걸려 409가 나면 안 된다.
        if (galleryUrl != studio.galleryUrl && studioRepository.existsByGalleryUrl(galleryUrl)) {
            throw StudioException(StudioErrorCode.GALLERY_URL_DUPLICATED)
        }

        studio.update(name, galleryUrl)
        return studio
    }

    /**
     * 온보딩 화면이 입력 중에 물어보는 값.
     *
     * 형식·예약어는 [Studio.isValidGalleryUrl]이 판단한다. 여기서 정규식을 따로 들고 있으면
     * "확인은 통과했는데 저장이 거절되는" 상황이 생긴다.
     *
     * 이 응답이 true라고 해서 생성이 반드시 성공하지는 않는다. 확인과 생성 사이에 다른 사람이
     * 같은 주소를 채갈 수 있다. 최종 판단은 유니크 제약과 생성 API의 409다.
     */
    fun isGalleryUrlAvailable(galleryUrl: String): Boolean {
        if (!Studio.isValidGalleryUrl(galleryUrl)) {
            throw StudioException(StudioErrorCode.INVALID_GALLERY_URL)
        }
        return !studioRepository.existsByGalleryUrl(galleryUrl)
    }
}

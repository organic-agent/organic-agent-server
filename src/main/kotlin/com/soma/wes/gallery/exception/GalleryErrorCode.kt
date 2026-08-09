package com.soma.wes.gallery.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class GalleryErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    INVALID_STATUS_TRANSITION(HttpStatus.BAD_REQUEST, "GALLERY_400_1", "현재 상태에서는 할 수 없는 동작입니다."),

    /**
     * 이미 지난 기한을 갤러리 생성·재오픈 요청에 넣은 경우.
     *
     * [SELECTION_DEADLINE_PASSED]와 다르다. 저쪽은 정상적으로 정해둔 기한이 흘러서 지난 것이라
     * 부부에게 나가는 403이고, 이쪽은 작가가 지금 보낸 값이 잘못됐다는 400이다.
     */
    INVALID_SELECTION_DEADLINE(HttpStatus.BAD_REQUEST, "GALLERY_400_2", "사진 선택 마감 기한은 현재 시각보다 뒤여야 합니다."),

    /**
     * 계약 장수로 0이나 음수가 들어온 경우.
     *
     * 한 장도 고를 수 없는 갤러리가 만들어지면, 막히는 것은 값을 넣은 작가가 아니라
     * 아무것도 못 고르는 부부다. 제한을 두지 않으려면 null을 보낸다.
     */
    INVALID_TARGET_PHOTO_COUNT(HttpStatus.BAD_REQUEST, "GALLERY_400_3", "선택 장수는 1 이상이어야 합니다."),

    /** 멤버도 담당 작가도 아닌 사용자의 접근. */
    GALLERY_ACCESS_DENIED(HttpStatus.FORBIDDEN, "GALLERY_403_1", "갤러리에 접근할 권한이 없습니다."),
    GALLERY_NOT_OPEN(HttpStatus.FORBIDDEN, "GALLERY_403_2", "지금은 사진을 고를 수 없는 갤러리입니다."),

    /**
     * 담당 작가가 자기 갤러리의 초대를 수락하려는 경우.
     *
     * 멤버가 되면 "작가는 고객 대신 사진을 고를 수 없다"는 규칙을 스스로 우회하게 된다.
     */
    MANAGER_CANNOT_ACCEPT_INVITE(HttpStatus.FORBIDDEN, "GALLERY_403_3", "담당 작가는 초대를 수락할 수 없습니다."),

    /**
     * [GALLERY_NOT_OPEN]과 나눠 둔다. 둘 다 "지금은 못 고른다"지만 사용자가 할 수 있는 일이 다르다.
     * 기한이 지난 것이라면 작가에게 연장을 요청하면 되고, 아직 안 열린 것이라면 기다리는 수밖에 없다.
     */
    SELECTION_DEADLINE_PASSED(HttpStatus.FORBIDDEN, "GALLERY_403_4", "사진 선택 마감 기한이 지났습니다."),

    GALLERY_NOT_FOUND(HttpStatus.NOT_FOUND, "GALLERY_404_1", "존재하지 않는 갤러리입니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "GALLERY_404_2", "갤러리 멤버가 아닙니다."),
    INVITE_NOT_FOUND(HttpStatus.NOT_FOUND, "GALLERY_404_3", "존재하지 않는 초대 링크입니다."),

    /**
     * 만료·폐기는 410으로 돌려준다. 링크 자체는 우리가 발급한 것이 맞고 지금은 쓸 수 없다는 뜻이라,
     * "그런 링크 없음"(404)과 구분해야 사용자에게 "작가에게 다시 요청하세요"를 안내할 수 있다.
     */
    INVITE_EXPIRED(HttpStatus.GONE, "GALLERY_410_1", "만료된 초대 링크입니다."),
    INVITE_REVOKED(HttpStatus.GONE, "GALLERY_410_2", "더 이상 사용할 수 없는 초대 링크입니다."),

    /** 기능 gate가 꺼져 있거나 샘플 manifest가 배포되지 않은 신규 Mock 갤러리 요청. */
    MOCK_GALLERY_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "GALLERY_503_1", "Mock 갤러리가 아직 준비되지 않았습니다."),
}

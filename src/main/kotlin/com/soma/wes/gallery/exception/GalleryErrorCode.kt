package com.soma.wes.gallery.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class GalleryErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    INVALID_STATUS_TRANSITION(HttpStatus.BAD_REQUEST, "GALLERY_400_1", "현재 상태에서는 할 수 없는 동작입니다."),

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
}

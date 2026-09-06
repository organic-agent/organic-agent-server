package com.soma.wes.gallery.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class GalleryErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    PERSONAL_CHECKOUT_REQUIRED(HttpStatus.PAYMENT_REQUIRED, "GALLERY_402_1", "개인 갤러리는 테스트 결제 완료 후 개설해 주세요."),
    GALLERY_ARCHIVED(HttpStatus.FORBIDDEN, "GALLERY_403_6", "종료된 갤러리는 열람만 가능합니다."),
    FOLDERS_ALREADY_SAVED(HttpStatus.CONFLICT, "GALLERY_409_2", "사진 정리가 이미 완료되었습니다."),
    INVALID_INCREASE_REQUEST(HttpStatus.BAD_REQUEST, "GALLERY_400_8", "현재 계약 장수보다 큰 장수를 요청해 주세요."),
    PHOTO_PLAN_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "GALLERY_409_3", "플랜의 업로드 가능 장수를 초과했습니다."),
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
    INVALID_MAX_SELECTABLE_PHOTO_COUNT(HttpStatus.BAD_REQUEST, "GALLERY_400_3", "선택 장수는 1 이상이어야 합니다."),

    /** [INVALID_MAX_SELECTABLE_PHOTO_COUNT]와 같은 이유다 — 0회짜리 보정 계약은 없고, 제한을 두지 않으려면 null을 보낸다. */
    INVALID_MAX_RETOUCH_ROUND_COUNT(HttpStatus.BAD_REQUEST, "GALLERY_400_4", "보정 횟수는 1 이상이어야 합니다."),
    INVALID_INVITE_KIND(HttpStatus.BAD_REQUEST, "GALLERY_400_5", "작업공간 종류와 맞지 않는 초대 종류입니다."),
    INVALID_INVITE_EXPIRY(HttpStatus.BAD_REQUEST, "GALLERY_400_6", "초대 만료 시각은 현재보다 뒤여야 합니다."),
    INVALID_INVITE_MAX_USES(HttpStatus.BAD_REQUEST, "GALLERY_400_7", "초대 사용 가능 횟수는 1~100이어야 합니다."),

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

    /**
     * 정원([com.soma.wes.gallery.domain.GalleryMember.MAX_PER_GALLERY])이 찬 갤러리에 수락을 시도한 경우.
     *
     * 404가 아니라 403이다. 링크도 갤러리도 멀쩡히 존재하고, 막힌 이유가 "당신 자리가 없다"라는
     * 사실을 알려줘야 링크를 잘못 받은 사람이 작가에게 문의할 수 있다.
     */
    GALLERY_MEMBER_LIMIT_EXCEEDED(HttpStatus.FORBIDDEN, "GALLERY_403_5", "이미 정원이 찬 갤러리입니다."),

    GALLERY_NOT_FOUND(HttpStatus.NOT_FOUND, "GALLERY_404_1", "존재하지 않는 갤러리입니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "GALLERY_404_2", "갤러리 멤버가 아닙니다."),
    INVITE_NOT_FOUND(HttpStatus.NOT_FOUND, "GALLERY_404_3", "존재하지 않는 초대 링크입니다."),
    INVITE_INVALID(HttpStatus.NOT_FOUND, "GALLERY_404_4", "유효하지 않은 초대 링크입니다."),

    INVITE_FULL(HttpStatus.CONFLICT, "GALLERY_409_1", "초대 링크의 사용 가능 횟수를 모두 소진했습니다."),

    /**
     * 만료·폐기는 410으로 돌려준다. 링크 자체는 우리가 발급한 것이 맞고 지금은 쓸 수 없다는 뜻이라,
     * "그런 링크 없음"(404)과 구분해야 사용자에게 "작가에게 다시 요청하세요"를 안내할 수 있다.
     */
    INVITE_EXPIRED(HttpStatus.GONE, "GALLERY_410_1", "만료된 초대 링크입니다."),
    INVITE_REVOKED(HttpStatus.GONE, "GALLERY_410_2", "더 이상 사용할 수 없는 초대 링크입니다."),

    /**
     * 샘플 템플릿 갤러리가 아직 준비되지 않은 Mock 갤러리 요청.
     *
     * 설정(`app.mock-gallery.template-gallery-id`)이 비었거나, 가리키는 갤러리가 없거나,
     * 임베딩까지 끝난 사진이 한 장도 없는 경우다. 셋 다 운영자가 시드를 마치면 풀린다.
     */
    MOCK_GALLERY_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "GALLERY_503_1", "샘플 갤러리가 아직 준비되지 않았습니다."),
}

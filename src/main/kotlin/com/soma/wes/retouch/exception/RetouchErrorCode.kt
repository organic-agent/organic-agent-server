package com.soma.wes.retouch.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class RetouchErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    /**
     * 계약한 보정 횟수를 다 써서 더 제출(또는 새 회차 시작)할 수 없는 경우.
     *
     * 제출이 최종 관문이지만 새 회차를 만드는 순간에도 같은 검사를 한다 — 어차피 제출하지
     * 못할 회차에 사진을 모으게 두면, 부부는 요청을 다 적고 나서야 거절당한다.
     */
    MAX_RETOUCH_ROUND_COUNT_EXCEEDED(HttpStatus.BAD_REQUEST, "RETOUCH_400_1", "계약한 보정 횟수를 모두 사용했습니다."),

    /** 요청에 이 갤러리의 사진이 아닌 id가 섞여 있는 경우. */
    PHOTO_NOT_IN_GALLERY(HttpStatus.BAD_REQUEST, "RETOUCH_400_2", "이 갤러리의 사진이 아닙니다."),

    /** 한 요청에서 다룰 수 있는 사진 수(`app.storage.max-batch-size`)를 넘긴 경우. */
    TOO_MANY_PHOTOS(HttpStatus.BAD_REQUEST, "RETOUCH_400_3", "한 번에 처리할 수 있는 사진 수를 넘었습니다."),

    /**
     * 사진 id가 하나도 없는 경우.
     *
     * `@field:NotEmpty`가 컨트롤러에서 먼저 걸러내지만 그 검증은 컨트롤러를 지날 때만 돈다.
     */
    EMPTY_PHOTO_IDS(HttpStatus.BAD_REQUEST, "RETOUCH_400_4", "사진을 하나 이상 지정해야 합니다."),

    /**
     * 아직 업로드가 끝나지 않은(PENDING) 사진을 담으려는 경우.
     *
     * selection과 같은 규칙이다 — 보정을 맡길 사진은 실물이 있어야 한다. 실체가 없는 사진이
     * 섞이면 작가는 목록에는 있는데 열리지 않는 요청을 받는다.
     */
    PHOTO_NOT_UPLOADED(HttpStatus.BAD_REQUEST, "RETOUCH_400_5", "아직 업로드가 끝나지 않은 사진은 담을 수 없습니다."),

    /**
     * 제출할 것이 없는 경우 — 진행 중인 DRAFTING 회차가 없거나, 있어도 담긴 사진이 없다.
     * 제출 직후 다시 제출하는 중복 요청도 (회차가 이미 REQUESTED가 되어) 여기로 떨어진다.
     */
    EMPTY_ROUND(HttpStatus.BAD_REQUEST, "RETOUCH_400_6", "보정을 요청할 사진이 없어 제출할 수 없습니다."),

    /** 요청 텍스트가 [com.soma.wes.retouch.domain.RetouchPhoto.MAX_REQUEST_TEXT_LENGTH]를 넘긴 경우. */
    REQUEST_TEXT_TOO_LONG(HttpStatus.BAD_REQUEST, "RETOUCH_400_7", "요청 내용이 너무 깁니다."),

    /**
     * 이 갤러리의 주석 업로드 경로가 아닌 storage key를 저장하려는 경우.
     *
     * 서버는 발급한 key를 따로 기억하지 않으므로 접두 검사가 유일한 방어다 — 이것이 없으면
     * 다른 갤러리의 사진 key를 주석으로 걸어 남의 원본을 자기 화면에 서명해 볼 수 있다.
     */
    INVALID_ANNOTATION_KEY(HttpStatus.BAD_REQUEST, "RETOUCH_400_8", "이 갤러리의 주석 이미지가 아닙니다."),

    /** 진행 중인 DRAFTING 회차에 없는 사진을 빼거나 요청을 적으려는 경우. */
    PHOTO_NOT_IN_ROUND(HttpStatus.NOT_FOUND, "RETOUCH_404_1", "보정 요청 목록에 없는 사진입니다."),

    /**
     * 이전 회차의 결과를 기다리는 중에 새로 담으려는 경우.
     *
     * 회차는 갤러리당 하나씩만 진행된다. 결과가 오기 전에 다음 요청이 쌓이면 "몇 회 남았는지"가
     * 흐려지고 회차별 전/후 비교의 전제도 깨진다. 화면은 이 코드를 보고 "작가의 응답을
     * 기다리는 중"으로 안내할 수 있다.
     */
    ROUND_IN_PROGRESS(HttpStatus.CONFLICT, "RETOUCH_409_1", "이전 보정 회차가 아직 진행 중입니다."),

    /**
     * 이미 이번 회차에 담긴 사진을 또 담으려는 경우.
     *
     * selection과 같은 이유로 통째로 거절한다 — 부부 둘이 각자의 화면에서 담는 물건이라,
     * 겹쳤다는 것은 보고 있는 화면이 낡았다는 뜻이다.
     */
    PHOTO_ALREADY_IN_ROUND(HttpStatus.CONFLICT, "RETOUCH_409_2", "이미 이번 회차에 담긴 사진입니다."),

    /** 현재 상태에서 허용되지 않는 회차 상태 전이. 서비스가 막고 남은 마지막 방어선이다. */
    INVALID_ROUND_STATUS(HttpStatus.CONFLICT, "RETOUCH_409_3", "현재 상태에서는 할 수 없는 회차 동작입니다."),
}

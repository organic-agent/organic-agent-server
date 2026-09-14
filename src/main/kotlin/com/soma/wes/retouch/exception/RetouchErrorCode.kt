package com.soma.wes.retouch.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class RetouchErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    // 쓰지 않는 회차 편집 API(빼기·초안 제출·주석 업로드)와 함께 지운 코드:
    // RETOUCH_400_6(EMPTY_ROUND) · RETOUCH_400_8(INVALID_ANNOTATION_KEY).
    // 번호는 재사용하지 않는다 — 클라이언트가 옛 코드로 분기할 수 있다.

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

    /** 요청 텍스트가 [com.soma.wes.retouch.domain.RetouchPhoto.MAX_REQUEST_TEXT_LENGTH]를 넘긴 경우. */
    REQUEST_TEXT_TOO_LONG(HttpStatus.BAD_REQUEST, "RETOUCH_400_7", "요청 내용이 너무 깁니다."),


    /** 결과 업로드 URL 발급에 허용 목록 밖의 Content-Type을 보낸 경우. */
    UNSUPPORTED_CONTENT_TYPE(HttpStatus.BAD_REQUEST, "RETOUCH_400_9", "지원하지 않는 이미지 형식입니다."),

    /**
     * 이 회차의 결과 업로드 경로가 아닌 storage key를 확정하려는 경우.
     *
     * 서버는 발급한 key를 기억하지 않으므로 접두 검사가 유일한 방어고, 회차 번호까지 접두에
     * 들어 있어 다른 회차의 결과를 섞을 수도 없다.
     */
    INVALID_RESULT_KEY(HttpStatus.BAD_REQUEST, "RETOUCH_400_10", "이 회차의 보정 결과가 아닙니다."),

    /**
     * 결과가 없는 항목이 남았는데 회차를 끝내려는 경우.
     *
     * 회차 완료는 "요청 전부에 응답했다"는 선언이다 — 일부만 응답한 채 끝내면 부부는 어떤
     * 사진이 누락됐는지 알 수 없고, 다음 회차에서 계약 횟수만 더 쓰게 된다.
     */
    MISSING_RESULT(HttpStatus.BAD_REQUEST, "RETOUCH_400_11", "아직 결과가 없는 사진이 있어 회차를 끝낼 수 없습니다."),

    /** 선택 앨범에 담으려는 retouchPhotoId가 이 갤러리의 보정 항목이 아닌 경우. */
    RETOUCH_PHOTO_NOT_IN_GALLERY(HttpStatus.BAD_REQUEST, "RETOUCH_400_12", "이 갤러리의 보정 항목이 아닙니다."),

    /**
     * retouchPhotoId가 가리키는 보정 항목의 원본이 요청의 photoId와 다른 경우.
     *
     * 항목의 photoId는 항상 원본이어야 중복·정원 규칙이 산다 — 짝이 어긋난 채 저장되면
     * 앨범에는 A컷이 담겼는데 화면에는 B컷의 보정본이 걸린다.
     */
    RETOUCH_PHOTO_MISMATCH(HttpStatus.BAD_REQUEST, "RETOUCH_400_13", "보정 항목의 원본 사진이 요청과 다릅니다."),

    /** 아직 작가의 결과가 없는 보정 항목을 선택 앨범에 담으려는 경우. */
    RESULT_NOT_UPLOADED(HttpStatus.BAD_REQUEST, "RETOUCH_400_14", "아직 결과가 없는 보정 항목은 담을 수 없습니다."),

    REFINEMENT_FAILED(HttpStatus.BAD_GATEWAY, "RETOUCH_502_1", "보정 요청 정제 응답이 올바르지 않습니다."),

    INVALID_POINT(HttpStatus.BAD_REQUEST, "RETOUCH_400_15", "보정 지점의 좌표나 요청 내용이 올바르지 않습니다."),
    RESULT_UPLOAD_INCOMPLETE(HttpStatus.BAD_REQUEST, "RETOUCH_400_16", "실제 업로드가 완료되지 않은 보정본입니다."),
    PHOTO_NOT_SELECTED(HttpStatus.BAD_REQUEST, "RETOUCH_400_17", "선택 목록에 있는 사진만 보정을 요청할 수 있습니다."),
    DUPLICATE_RESULT(HttpStatus.BAD_REQUEST, "RETOUCH_400_18", "중복된 사진이나 결과 파일이 포함되어 있습니다."),
    INVALID_FILENAME(HttpStatus.BAD_REQUEST, "RETOUCH_400_19", "보정 파일명이 올바르지 않습니다."),

    /** 진행 중인 DRAFTING 회차에 없는 사진을 빼거나 요청을 적으려는 경우. */
    PHOTO_NOT_IN_ROUND(HttpStatus.NOT_FOUND, "RETOUCH_404_1", "보정 요청 목록에 없는 사진입니다."),

    /** 이 갤러리에 없는 회차 번호로 조회·결과 업로드를 시도한 경우. */
    ROUND_NOT_FOUND(HttpStatus.NOT_FOUND, "RETOUCH_404_2", "존재하지 않는 보정 회차입니다."),

    /**
     * 이전 회차의 결과를 기다리는 중에 새로 담으려는 경우.
     *
     * 회차는 갤러리당 하나씩만 진행된다. 결과가 오기 전에 다음 요청이 쌓이면 "몇 회 남았는지"가
     * 흐려지고 회차별 전/후 비교의 전제도 깨진다.
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

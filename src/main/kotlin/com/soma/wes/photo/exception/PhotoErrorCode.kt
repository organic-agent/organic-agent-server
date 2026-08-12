package com.soma.wes.photo.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class PhotoErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    /** 한 요청에서 발급·조회할 수 있는 개수(`app.storage.max-batch-size`)를 넘긴 경우. */
    TOO_MANY_PHOTOS(HttpStatus.BAD_REQUEST, "PHOTO_400_1", "한 번에 처리할 수 있는 사진 수를 넘었습니다."),

    /** 지원하지 않는 형식. 임베딩 Lambda가 디코딩할 수 있는 것만 받는다. */
    UNSUPPORTED_CONTENT_TYPE(HttpStatus.BAD_REQUEST, "PHOTO_400_2", "지원하지 않는 이미지 형식입니다."),

    /**
     * 벡터 폭이 `photos.embedding`의 `vector(n)`과 다른 경우.
     *
     * 정상 경로에서는 DB가 먼저 거절하지만, 그때는 이미 배치 하나를 통째로 계산한 뒤다.
     */
    EMBEDDING_DIMENSION_MISMATCH(HttpStatus.BAD_REQUEST, "PHOTO_400_3", "임베딩 차원이 올바르지 않습니다."),

    /**
     * 별점이 1~5 밖인 경우. 목록을 거르는 `minScore`도 같은 범위를 쓴다.
     *
     * 컨트롤러의 `@Min`·`@Max`가 대부분 먼저 걸러내지만, 그 검증은 컨트롤러를 지날 때만 돈다.
     */
    INVALID_SCORE(HttpStatus.BAD_REQUEST, "PHOTO_400_4", "별점은 1점에서 5점 사이여야 합니다."),

    // PHOTO_400_5는 페이지 번호 검증용이었으나 com.soma.wes.global.page.PageRequests가
    // 거절 대신 절삭하도록 바뀌며 사라졌다. 뒤 번호를 당기면 살아 있는 코드의 계약이
    // 바뀌므로 구멍을 그대로 둔다.

    /** 요청에 다른 갤러리의 사진 id가 섞여 있는 경우. */
    PHOTO_NOT_FOUND(HttpStatus.NOT_FOUND, "PHOTO_404_1", "존재하지 않는 사진입니다."),

    /** Lambda 호출 자체가 실패한 경우(권한·스로틀링 등). 임베딩 계산 실패와는 다르다. */
    EMBEDDING_INVOCATION_FAILED(HttpStatus.BAD_GATEWAY, "PHOTO_502_1", "임베딩 실행을 시작하지 못했습니다."),

    /** 운영자 승인 삭제에서 S3 원본 또는 미리보기를 모두 지우지 못한 경우. */
    STORAGE_DELETE_FAILED(HttpStatus.BAD_GATEWAY, "PHOTO_502_2", "사진 원본 또는 미리보기 삭제를 완료하지 못했습니다."),

    /**
     * 임베딩 함수 이름이 설정되지 않은 경우.
     *
     * 로컬·테스트에는 Lambda가 없는 것이 정상이라 기동을 막지 않는다. 대신 실제로 부르려는
     * 순간에 여기서 멈춘다 — "설정이 비어 있다"와 "함수가 죽었다"는 대응이 다르므로 구분한다.
     */
    EMBEDDING_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "PHOTO_503_1", "임베딩 실행이 설정되지 않았습니다."),
}

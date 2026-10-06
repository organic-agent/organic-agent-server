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
     * 벡터 폭이 `photo_analysis.embedding`의 `vector(n)`과 다른 경우.
     *
     * 정상 경로(임베더 Lambda)에서는 DB가 거절한다. 이 코드는 이 서버가 벡터를 복제하는 우회로
     * (`PhotoAnalysis.embeddedBy`)가 던진다.
     */
    EMBEDDING_DIMENSION_MISMATCH(HttpStatus.BAD_REQUEST, "PHOTO_400_3", "임베딩 차원이 올바르지 않습니다."),

    /**
     * 별점이 1~5 밖인 경우. 목록을 거르는 `minScore`도 같은 범위를 쓴다.
     *
     * 컨트롤러의 `@Min`·`@Max`가 대부분 먼저 걸러내지만, 그 검증은 컨트롤러를 지날 때만 돈다.
     */
    INVALID_SCORE(HttpStatus.BAD_REQUEST, "PHOTO_400_4", "별점은 1점에서 5점 사이여야 합니다."),

    /** 발급 요청의 바이트 수가 상한을 넘거나 0 이하다. 프론트가 리사이즈를 건너뛴 신호라 발급 단계에서 막는다. */
    INVALID_CONTENT_LENGTH(HttpStatus.BAD_REQUEST, "PHOTO_400_7", "업로드할 사진 크기가 허용 범위를 벗어났습니다."),

    /**
     * 발급 요청의 CRC32C가 base64 8자 형식이 아니다. 값이 서명에 그대로 들어가므로 여기서 거르지 않으면 S3가 PUT을 거절할
     * 때까지 드러나지 않는다.
     */
    INVALID_CHECKSUM(HttpStatus.BAD_REQUEST, "PHOTO_400_8", "업로드할 사진의 CRC32C 체크섬 형식이 올바르지 않습니다."),

    /** URL 재발급은 아직 올라오지 않은(PENDING) 사진에만 뜻이 있다. 이미 올라온 사진에 새 PUT URL을 주면 원본이 덮인다. */
    PHOTO_ALREADY_UPLOADED(HttpStatus.CONFLICT, "PHOTO_409_1", "이미 업로드가 끝난 사진입니다."),

    // PHOTO_400_5는 페이지 번호 검증용이었으나 com.soma.wes.global.page.PageRequests가
    // 거절 대신 절삭하도록 바뀌며 사라졌다. 뒤 번호를 당기면 살아 있는 코드의 계약이
    // 바뀌므로 구멍을 그대로 둔다.

    /** 요청에 다른 갤러리의 사진 id가 섞여 있는 경우. */
    PHOTO_NOT_FOUND(HttpStatus.NOT_FOUND, "PHOTO_404_1", "존재하지 않는 사진입니다."),

    /** 인증된 부부의 내부 댓글. 하객 협업 댓글과는 별도 자원이다. */
    INVALID_COMMENT(HttpStatus.BAD_REQUEST, "PHOTO_400_6", "댓글은 비어 있을 수 없고 500자 이하여야 합니다."),
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PHOTO_404_2", "존재하지 않는 사진 댓글입니다."),
    COMMENT_DELETE_DENIED(HttpStatus.FORBIDDEN, "PHOTO_403_1", "본인이 작성한 댓글만 삭제할 수 있습니다."),

    /** 사진 메모(`PhotoMemo`)가 비었거나 상한을 넘은 경우. 지우기는 DELETE다. */
    INVALID_MEMO(HttpStatus.BAD_REQUEST, "PHOTO_400_9", "메모는 비어 있을 수 없고 2000자 이하여야 합니다."),

    /** 운영자 승인 삭제에서 S3 원본 또는 미리보기를 모두 지우지 못한 경우. */
    STORAGE_DELETE_FAILED(HttpStatus.BAD_GATEWAY, "PHOTO_502_2", "사진 원본 또는 미리보기 삭제를 완료하지 못했습니다."),

    /** Mock 갤러리 생성에서 템플릿 객체의 S3 복사가 실패한 경우. 재시도하면 처음부터 다시 만든다. */
    STORAGE_COPY_FAILED(HttpStatus.BAD_GATEWAY, "PHOTO_502_3", "샘플 사진 복제를 완료하지 못했습니다."),

    /** 원본 교체 완료 검증에서 S3 메타데이터 조회 자체가 실패한 경우. */
    STORAGE_METADATA_FAILED(HttpStatus.BAD_GATEWAY, "PHOTO_502_4", "사진 원본의 업로드 상태를 확인하지 못했습니다."),

    /** AI 호출 재료로 미리보기를 읽지 못한 경우. 호출자(판정·이유)는 사진 없는 경로로 폴백한다. */
    STORAGE_READ_FAILED(HttpStatus.BAD_GATEWAY, "PHOTO_502_5", "사진 미리보기를 읽지 못했습니다."),

}

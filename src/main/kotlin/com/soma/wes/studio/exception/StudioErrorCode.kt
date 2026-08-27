package com.soma.wes.studio.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class StudioErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    INVALID_GALLERY_URL(HttpStatus.BAD_REQUEST, "STUDIO_400_1", "갤러리 주소는 소문자·숫자·하이픈으로 3~50자여야 하며, 사용할 수 없는 주소입니다.",),
    STUDIO_SELECTION_REQUIRED(HttpStatus.BAD_REQUEST, "STUDIO_400_3", "여러 스튜디오에 속해 있어 작업할 스튜디오를 지정해야 합니다."),

    NOT_STUDIO_OWNER(HttpStatus.FORBIDDEN, "STUDIO_403_1", "스튜디오 소유자만 할 수 있습니다."),
    STUDIO_ACCESS_DENIED(HttpStatus.FORBIDDEN, "STUDIO_403_2", "이 스튜디오를 운영할 권한이 없습니다."),
    STUDIO_NOT_FOUND(HttpStatus.NOT_FOUND, "STUDIO_404_1", "존재하지 않는 스튜디오입니다."),

    // STUDIO_400_2, STUDIO_409_3~10, STUDIO_503_1은 폐기된 운영자 hard delete가 쓰던 번호다.
    // 재사용하면 옛 클라이언트·로그와 뜻이 어긋나므로 비워 둔다.
    STUDIO_ALREADY_EXISTS(HttpStatus.CONFLICT, "STUDIO_409_1", "이미 스튜디오를 만들었습니다."),
    GALLERY_URL_DUPLICATED(HttpStatus.CONFLICT, "STUDIO_409_2", "이미 사용 중인 갤러리 주소입니다."),
}

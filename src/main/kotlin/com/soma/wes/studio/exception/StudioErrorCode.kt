package com.soma.wes.studio.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class StudioErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    INVALID_GALLERY_URL(HttpStatus.BAD_REQUEST, "STUDIO_400_1", "갤러리 주소는 소문자·숫자·하이픈으로 3~50자여야 하며, 사용할 수 없는 주소입니다.",),
    INVALID_DELETION_REQUEST(HttpStatus.BAD_REQUEST, "STUDIO_400_2", "삭제 요청 사유와 확인값이 올바르지 않습니다."),

    NOT_STUDIO_OWNER(HttpStatus.FORBIDDEN, "STUDIO_403_1", "스튜디오 소유자만 할 수 있습니다."),
    STUDIO_NOT_FOUND(HttpStatus.NOT_FOUND, "STUDIO_404_1", "존재하지 않는 스튜디오입니다."),

    STUDIO_ALREADY_EXISTS(HttpStatus.CONFLICT, "STUDIO_409_1", "이미 스튜디오를 만들었습니다."),
    GALLERY_URL_DUPLICATED(HttpStatus.CONFLICT, "STUDIO_409_2", "이미 사용 중인 갤러리 주소입니다."),
    DELETION_TARGET_MISMATCH(HttpStatus.CONFLICT, "STUDIO_409_3", "확인한 스튜디오와 삭제 대상이 일치하지 않습니다."),
    DELETION_REQUEST_CONFLICT(HttpStatus.CONFLICT, "STUDIO_409_4", "같은 삭제 요청 ID가 다른 내용으로 사용됐습니다."),
    DELETION_TARGET_CHANGED(HttpStatus.CONFLICT, "STUDIO_409_5", "삭제 준비 중 대상 데이터가 변경됐습니다. 같은 요청 ID로 다시 실행해주세요."),
    DELETION_REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "STUDIO_409_6", "같은 삭제 요청이 이미 실행 중입니다."),
    STUDIO_DELETION_IN_PROGRESS(HttpStatus.CONFLICT, "STUDIO_409_7", "스튜디오 삭제가 진행 중이라 새 데이터를 저장할 수 없습니다."),
    DELETION_UPLOAD_URL_ACTIVE(HttpStatus.CONFLICT, "STUDIO_409_8", "아직 유효한 사진 업로드 URL이 있어 삭제를 시작할 수 없습니다."),
    DELETION_WRITER_IN_PROGRESS(HttpStatus.CONFLICT, "STUDIO_409_9", "스튜디오 데이터 쓰기 작업이 진행 중이라 삭제를 시작할 수 없습니다."),
    DELETION_PLAN_VERSION_UNSUPPORTED(HttpStatus.CONFLICT, "STUDIO_409_10", "현재 서버가 이 삭제 재시도 plan 버전을 지원하지 않습니다."),
    HARD_DELETION_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "STUDIO_503_1", "스튜디오 hard delete가 아직 활성화되지 않았습니다."),
}

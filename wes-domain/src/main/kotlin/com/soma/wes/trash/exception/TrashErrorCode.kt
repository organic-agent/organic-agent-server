package com.soma.wes.trash.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class TrashErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    /**
     * 요청에 이 갤러리의 휴지통에 없는 사진 id가 섞여 있는 경우.
     *
     * 살아 있는 사진과 남의 갤러리 사진을 구분해 알려주지 않는다 — 구분해 주면 갤러리 하나로
     * 남의 사진 id의 존재 여부를 캐볼 수 있다. `findByIdAndGalleryId`와 같은 이유다.
     */
    PHOTO_NOT_IN_TRASH(HttpStatus.NOT_FOUND, "TRASH_404_1", "휴지통에 없는 사진입니다."),

    /** 휴지통에 없는 갤러리이거나 내 스튜디오의 것이 아닌 경우. 위와 같은 이유로 구분하지 않는다. */
    GALLERY_NOT_IN_TRASH(HttpStatus.NOT_FOUND, "TRASH_404_2", "휴지통에 없는 갤러리입니다."),

    /**
     * 아직 유효한 업로드 URL이 있어 즉시 물리 삭제를 거절한 경우.
     *
     * DB 행을 지워도 URL은 취소되지 않는다. URL을 가진 클라이언트가 삭제 뒤 같은 키로 PUT하면
     * 아무 행도 가리키지 않는 S3 객체가 남는다. URL 수명(30분)이 지나면 다시 시도할 수 있고,
     * 보관 기간 만료 purge는 이 간격이 보장돼 확인 없이 지운다.
     */
    UPLOAD_URL_ACTIVE(HttpStatus.CONFLICT, "TRASH_409_1", "아직 유효한 사진 업로드 URL이 있어 지금은 완전히 삭제할 수 없습니다."),
}

package com.soma.wes.album.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class AlbumErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {
    INVALID_LAYOUT(HttpStatus.BAD_REQUEST, "ALBUM_400_1", "앨범 페이지, 프레임 또는 안내선이 올바르지 않습니다."),
    INVALID_ASSIGNMENT(HttpStatus.BAD_REQUEST, "ALBUM_400_2", "선택한 사진만 프레임마다 한 장씩 넣을 수 있습니다."),
    TOO_MANY_FRAMES(HttpStatus.BAD_REQUEST, "ALBUM_400_3", "프레임 수는 선택한 사진 수를 넘을 수 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "ALBUM_404_1", "열람할 수 있는 앨범이 없습니다."),
    /** 자동 저장이 충돌하면 최신 문서와 버전을 다시 읽고 변경을 합쳐야 한다. */
    REVISION_MISMATCH(HttpStatus.CONFLICT, "ALBUM_409_1", "앨범이 다른 화면에서 변경되었습니다. 최신 앨범을 다시 읽어 주세요."),
    INVALID_STATE(HttpStatus.CONFLICT, "ALBUM_409_2", "현재 앨범 단계에서는 이 작업을 할 수 없습니다."),
    INCOMPLETE(HttpStatus.CONFLICT, "ALBUM_409_3", "선택한 사진을 모두 프레임에 한 장씩 배치해 주세요."),
    PAGE_SIZE_LOCKED(HttpStatus.CONFLICT, "ALBUM_409_4", "앨범을 만든 뒤에는 페이지 크기를 바꿀 수 없습니다."),
    CLIENT_LAYOUT_LOCKED(HttpStatus.CONFLICT, "ALBUM_409_5", "고객은 발송된 프레임의 사진 배치와 위치만 수정할 수 있습니다."),
}

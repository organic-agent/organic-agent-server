package com.soma.wes.folder.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class FolderErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    /**
     * 요청에 이 갤러리의 사진이 아닌 id가 섞여 있는 경우.
     *
     * 갤러리 권한만 확인하고 사진 id를 그대로 믿으면, 자기 갤러리에 만든 폴더 하나로 남의 사진을
     * 끌어와 서명 URL까지 받아낼 수 있다.
     */
    PHOTO_NOT_IN_GALLERY(HttpStatus.BAD_REQUEST, "FOLDER_400_1", "이 갤러리의 사진이 아닙니다."),

    /** 한 요청에서 다룰 수 있는 사진 수(`app.storage.max-batch-size`)를 넘긴 경우. */
    TOO_MANY_PHOTOS(HttpStatus.BAD_REQUEST, "FOLDER_400_2", "한 번에 처리할 수 있는 사진 수를 넘었습니다."),

    /** 이름이 공백뿐이거나 길이를 넘긴 경우. */
    INVALID_FOLDER_NAME(HttpStatus.BAD_REQUEST, "FOLDER_400_3", "폴더 이름이 올바르지 않습니다."),

    FOLDER_NOT_FOUND(HttpStatus.NOT_FOUND, "FOLDER_404_1", "존재하지 않는 폴더입니다."),

    /** 폴더에 들어 있지 않은 사진을 빼려는 경우. */
    PHOTO_NOT_IN_FOLDER(HttpStatus.NOT_FOUND, "FOLDER_404_2", "폴더에 없는 사진입니다."),
}

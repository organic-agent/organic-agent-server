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

    /**
     * 사진 id가 하나도 없는 경우.
     *
     * `@field:NotEmpty`가 컨트롤러에서 먼저 걸러내지만 그 검증은 컨트롤러를 지날 때만 돈다.
     * 여기서 막지 않으면 사진 없는 폴더가 만들어지고, 목록에 0장짜리로 남는다.
     */
    EMPTY_PHOTO_IDS(HttpStatus.BAD_REQUEST, "FOLDER_400_4", "사진을 하나 이상 지정해야 합니다."),

    FOLDER_NOT_FOUND(HttpStatus.NOT_FOUND, "FOLDER_404_1", "존재하지 않는 폴더입니다."),

    /** 폴더에 들어 있지 않은 사진을 빼거나 옮기려는 경우. */
    PHOTO_NOT_IN_FOLDER(HttpStatus.NOT_FOUND, "FOLDER_404_2", "폴더에 없는 사진입니다."),

    GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "FOLDER_404_3", "존재하지 않는 부모 폴더입니다."),

    /**
     * 부모폴더 아래 어딘가에 이미 든 사진을 다시 담으려는 경우. 전체를 거절한다(409).
     *
     * 같은 부모 안에서 사진은 한 자식에만 속하므로, 중복 요청은 그 사진이 이미 다른 자식에
     * 있다는 뜻이고 대개 호출한 화면이 낡았다는 신호다. 몇 장만 조용히 건너뛰면 성공처럼
     * 보이는데 무엇이 왜 빠졌는지 아무도 말할 수 없다 — 선택 앨범이 초과·중복을 통째로
     * 거절하는 것과 같은 이유다.
     */
    DUPLICATE_PHOTO_IN_GROUP(HttpStatus.CONFLICT, "FOLDER_409_1", "이미 같은 부모 폴더에 담긴 사진입니다."),
}

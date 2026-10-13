package com.soma.wes.selection.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class SelectionErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    /**
     * 계약 장수를 넘겨 담으려는 경우.
     *
     * 들어갈 수 있는 만큼만 담고 나머지를 버리지 않는다. 30장을 골라 보냈는데 22장만 담기면
     * 화면에는 성공으로 보이고, 어느 8장이 빠졌는지는 아무도 모른다.
     */
    MAX_SELECTABLE_PHOTO_COUNT_EXCEEDED(HttpStatus.BAD_REQUEST, "SELECTION_400_1", "계약한 선택 장수를 넘길 수 없습니다."),

    /** 요청에 이 갤러리의 사진이 아닌 id가 섞여 있는 경우. */
    PHOTO_NOT_IN_GALLERY(HttpStatus.BAD_REQUEST, "SELECTION_400_2", "이 갤러리의 사진이 아닙니다."),

    /** 한 요청에서 다룰 수 있는 사진 수(`app.storage.max-batch-size`)를 넘긴 경우. */
    TOO_MANY_PHOTOS(HttpStatus.BAD_REQUEST, "SELECTION_400_3", "한 번에 처리할 수 있는 사진 수를 넘었습니다."),

    /**
     * 사진 id가 하나도 없는 경우.
     *
     * `@field:NotEmpty`가 컨트롤러에서 먼저 걸러내지만 그 검증은 컨트롤러를 지날 때만 돈다.
     */
    EMPTY_PHOTO_IDS(HttpStatus.BAD_REQUEST, "SELECTION_400_4", "사진을 하나 이상 지정해야 합니다."),

    /**
     * 아직 업로드가 끝나지 않은(PENDING) 사진을 고르려는 경우.
     *
     * 선택 앨범은 작가가 받아 보정에 들어가는 납품 목록이다. 실체가 없는 사진이 섞이면
     * 작가는 목록에는 있는데 열리지 않는 항목을 받는다. 폴더가 PENDING을 허용하는 것과
     * 다른데, 폴더는 중간 정리 도구라 올라오는 중인 사진을 미리 묶어둘 수 있어야 한다.
     */
    PHOTO_NOT_UPLOADED(HttpStatus.BAD_REQUEST, "SELECTION_400_5", "아직 업로드가 끝나지 않은 사진은 고를 수 없습니다."),

    /** 한 장도 고르지 않은 앨범을 제출하려는 경우. */
    EMPTY_SELECTION(HttpStatus.BAD_REQUEST, "SELECTION_400_6", "고른 사진이 없어 제출할 수 없습니다."),

    EXACT_TARGET_REQUIRED(HttpStatus.BAD_REQUEST, "SELECTION_400_7", "정확히 목표 장수를 선택해야 전달하거나 내보낼 수 있습니다."),
    PERSONAL_EXPORT_REQUIRED(HttpStatus.CONFLICT, "SELECTION_409_6", "개인 갤러리는 제출 대신 요청서를 내보내야 합니다."),
    SELECTION_ALREADY_EXPORTED(HttpStatus.CONFLICT, "SELECTION_409_5", "요청서를 내보낸 선택 목록은 변경할 수 없습니다."),
    PHOTO_ORGANIZATION_REQUIRED(HttpStatus.CONFLICT, "SELECTION_409_4", "사진 정리를 폴더로 저장한 후 선택할 수 있습니다."),

    /** 앨범에 없는 사진을 빼려는 경우. */
    PHOTO_NOT_SELECTED(HttpStatus.NOT_FOUND, "SELECTION_404_1", "선택 앨범에 없는 사진입니다."),

    /**
     * 제출된 앨범을 바꾸려는 경우.
     *
     * 400이 아니라 409다. 요청 자체는 멀쩡하고, 앨범의 상태가 지금 그것을 받을 수 없을 뿐이다.
     * 화면은 이 코드를 보고 "작가에게 제출 취소를 요청하세요"로 안내할 수 있다.
     */
    SELECTION_ALREADY_SUBMITTED(HttpStatus.CONFLICT, "SELECTION_409_1", "이미 제출한 선택 앨범입니다."),

    /** 제출되지 않은 앨범을 되돌리려는 경우. */
    SELECTION_NOT_SUBMITTED(HttpStatus.CONFLICT, "SELECTION_409_2", "아직 제출되지 않은 선택 앨범입니다."),

    /**
     * 이미 담긴 사진을 또 담으려는 경우.
     *
     * 조용히 건너뛰지 않는다. 부부 둘이 각자의 화면에서 고르는 물건이라 "이미 담겨 있다"는
     * 사실 자체가 사용자에게 필요한 정보다 — 건너뛰면 신부는 자기가 방금 담았다고 생각하고,
     * 나중에 신랑이 그 사진을 빼면 아무도 그 사진이 왜 사라졌는지 모른다.
     *
     * [MAX_SELECTABLE_PHOTO_COUNT_EXCEEDED]와 같이 통째로 막는다. 한 장이라도 겹치면 그 요청은
     * 사용자가 보고 있는 화면이 낡았다는 뜻이라, 일부만 담아두면 화면과 실제가 더 벌어진다.
     */
    PHOTO_ALREADY_SELECTED(HttpStatus.CONFLICT, "SELECTION_409_3", "이미 선택 앨범에 담긴 사진입니다."),
}

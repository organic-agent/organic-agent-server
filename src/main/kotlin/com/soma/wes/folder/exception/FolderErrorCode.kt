package com.soma.wes.folder.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class FolderErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {
    CONCEPT_NOT_FOUND(HttpStatus.NOT_FOUND, "CATEGORY_404_1", "컨셉폴더를 찾을 수 없습니다."),
    DETAIL_NOT_FOUND(HttpStatus.NOT_FOUND, "CATEGORY_404_2", "세부폴더를 찾을 수 없습니다."),
    /** 없는 합치기이거나, 원본 폴더가 지워졌거나(컨셉 삭제 포함), 되돌릴 시간이 지나 정리 작업이 기록을 지웠다. */
    MERGE_NOT_FOUND(HttpStatus.NOT_FOUND, "CATEGORY_404_3", "합치기 기록을 찾을 수 없습니다."),
    PHOTO_NOT_FOUND(HttpStatus.BAD_REQUEST, "CATEGORY_400_1", "갤러리에 없는 사진이 포함되어 있습니다."),
    EMPTY_PHOTO_IDS(HttpStatus.BAD_REQUEST, "CATEGORY_400_2", "이동할 사진이 없습니다."),
    MERGE_INTO_SELF(HttpStatus.BAD_REQUEST, "CATEGORY_400_3", "같은 세부폴더끼리는 합칠 수 없습니다."),
    INITIAL_ALREADY_COMPLETED(HttpStatus.CONFLICT, "CATEGORY_409_1", "최초 전체 카테고리화는 한 번만 실행할 수 있습니다."),
    ANALYSIS_NOT_COMPLETE(HttpStatus.CONFLICT, "CATEGORY_409_2", "AI 분석이 끝나지 않아 카테고리를 만들 수 없습니다."),
    NO_PHOTOS_TO_ORGANIZE(HttpStatus.CONFLICT, "CATEGORY_409_3", "새로 카테고리화할 사진이 없습니다."),
    MERGE_ALREADY_UNDONE(HttpStatus.CONFLICT, "CATEGORY_409_4", "이미 되돌린 합치기입니다."),
    /** 합친 뒤 `app.folder.merge-undo-window`(3분)가 지났다. 웹은 알림이 떠 있는 몇 초 동안만 되돌린다. */
    MERGE_UNDO_EXPIRED(HttpStatus.CONFLICT, "CATEGORY_409_5", "되돌릴 수 있는 시간이 지났습니다."),
    /** 합친 뒤 옮긴 사진이 다른 곳으로 다시 옮겨졌거나, 원본의 컨셉 · 대상 폴더가 사라졌다 — 되돌리면 그 사이의 변경을 덮어쓴다. */
    MERGE_UNDO_CONFLICT(HttpStatus.CONFLICT, "CATEGORY_409_6", "합친 뒤 폴더나 사진이 바뀌어 되돌릴 수 없습니다."),
}

package com.soma.wes.category.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class CategoryErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {
    CONCEPT_NOT_FOUND(HttpStatus.NOT_FOUND, "CATEGORY_404_1", "컨셉폴더를 찾을 수 없습니다."),
    DETAIL_NOT_FOUND(HttpStatus.NOT_FOUND, "CATEGORY_404_2", "세부폴더를 찾을 수 없습니다."),
    PHOTO_NOT_FOUND(HttpStatus.BAD_REQUEST, "CATEGORY_400_1", "갤러리에 없는 사진이 포함되어 있습니다."),
    EMPTY_PHOTO_IDS(HttpStatus.BAD_REQUEST, "CATEGORY_400_2", "이동할 사진이 없습니다."),
    INITIAL_ALREADY_COMPLETED(HttpStatus.CONFLICT, "CATEGORY_409_1", "최초 전체 카테고리화는 한 번만 실행할 수 있습니다."),
    ANALYSIS_NOT_COMPLETE(HttpStatus.CONFLICT, "CATEGORY_409_2", "AI 분석이 끝나지 않아 카테고리를 만들 수 없습니다."),
    NO_PHOTOS_TO_ORGANIZE(HttpStatus.CONFLICT, "CATEGORY_409_3", "새로 카테고리화할 사진이 없습니다."),
}

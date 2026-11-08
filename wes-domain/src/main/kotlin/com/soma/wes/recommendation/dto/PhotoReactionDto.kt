package com.soma.wes.recommendation.dto

/**
 * 사진 한 장에 대한 부부·하객 반응을 한 줄로 모은 것 — 추천과 (나중의) 계수 학습이 읽는 단위다.
 * 저장된 값이 아니라 원래 표(`photo_ratings`·`photo_selection_items`·`collab_photo_likes`)에서 추천 때마다 합친다.
 * 반응이 하나도 없는 사진은 줄이 없다.
 */
data class PhotoReactionDto(
    val photoId: Long,
    /** 부부 별점 1~5. 매기지 않았으면 null. */
    val rating: Int? = null,
    /** 선택 앨범에 담겼는지. */
    val selected: Boolean = false,
    /** 협업 세션 하객이 누른 좋아요 수(모든 세션 합). */
    val guestLikes: Int = 0,
)

package com.soma.wes.recommendation.dto

/**
 * 사진 한 장에 대한 부부·하객 반응을 한 줄로 모은 것 — 추천과 (나중의) 계수 학습이 읽는 단위다.
 * 저장된 값이 아니라 원래 표(`photo_ratings`·`photo_selection_items`·`collab_photo_likes`·`ai_recommendations`)에서 추천 때마다 합친다.
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
    /**
     * 추천에 보여준 횟수(거절 제외, 지금 보이는 추천 포함) — "보여줬는데 안 담음". 다음 추천을 계산할 때 읽으므로 재추천 요청 자체가
     * 지금 보이는 추천을 넘긴 것이다. 담은 사진은 후보에서 빠지므로 호출자가 뺀다. 별점을 매긴 뒤의 노출만 센다:
     * 무시한 뒤 5점을 줬다면 마음이 바뀐 것이라 그 전 노출은 세지 않는다(#391 3단계).
     */
    val ignored: Int = 0,
)

package com.soma.wes.agent.domain

/**
 * 도우미가 거는 사진 조건. 비어 있는 칸은 조건이 없다는 뜻이다.
 *
 * 화면의 필터 칩과 같은 모양이라, 도우미가 건 조건을 사용자가 칩으로 고치거나 끄고 다시 보낼 수 있다.
 * 컨셉과 세부 폴더를 함께 주면 둘 중 하나에 들면 통과한다 — "야외 폴더랑 케이크 폴더"처럼 말하기 때문이다.
 * 반응(좋아요·댓글·별점·메모)은 읽기만 한다 — 도우미가 반응을 남기거나 지우지는 않는다(#348 ③).
 */
data class PhotoFilter(
    val conceptFolderIds: List<Long> = emptyList(),
    val detailFolderIds: List<Long> = emptyList(),
    val subjects: List<AgentSubject> = emptyList(),
    /** true면 고른 사진만, false면 아직 안 고른 사진만. */
    val selected: Boolean? = null,
    val orientation: PhotoOrientation? = null,
    /** 비슷한 연사 묶음마다 대표 한 장(`burst_rank = 0`)만. */
    val burstRepresentativeOnly: Boolean = false,
    /** true면 하객(공유 세션)이 좋아요를 누른 사진만, false면 아무도 안 누른 사진만. 휴지통에 든 좋아요는 안 센다. */
    val likedByGuests: Boolean? = null,
    /** true면 댓글(부부 댓글·하객 댓글 모두)이 달린 사진만, false면 댓글 없는 사진만. */
    val hasComments: Boolean? = null,
    /** 별점이 이 값 이상인 사진만(1~5). 별점 없는 사진은 안 걸린다. */
    val minRating: Int? = null,
    /** 별점이 이 값 이하인 사진만(1~5). "1점짜리"는 minRating = maxRating = 1이다. */
    val maxRating: Int? = null,
    /** true면 메모가 있는 사진만, false면 메모 없는 사진만. */
    val hasMemo: Boolean? = null,
    /** 이 사람이 선택에 담은 사진만(`photo_selection_items.added_by_user_id`). */
    val addedBy: AgentActor? = null,
    /** 마지막으로 별점을 매긴 사람이 이 사람인 사진만 — 별점은 사진당 한 줄이라 앞사람의 별점은 덮인다(`photo_ratings.rated_by`). */
    val ratedBy: AgentActor? = null,
    /** 마지막으로 메모를 쓴 사람이 이 사람인 사진만(`photo_memos.updated_by`, 사진당 한 줄). */
    val memoBy: AgentActor? = null,
    /** 이 사람이 부부 댓글을 단 사진만(`photo_comments.author_id`). 하객 댓글은 사람이 아니라 이름표라 안 본다. */
    val commentedBy: AgentActor? = null,
) {

    companion object {

        /** `photo_ratings.score` 범위(`ck_photo_ratings_score`). */
        const val MIN_RATING = 1
        const val MAX_RATING = 5
    }

    /** 프로퍼티가 아니라 함수다 — 응답 JSON에 칸으로 새어 나가지 않게. */
    fun hasFolder(): Boolean = conceptFolderIds.isNotEmpty() || detailFolderIds.isNotEmpty()

    /** 반응(좋아요·댓글·별점·메모)으로 거르는가 — 그 결과엔 반응 본문을 같이 싣는다. */
    fun hasReactionCondition(): Boolean =
        likedByGuests != null || hasComments != null || minRating != null || maxRating != null || hasMemo != null ||
            ratedBy != null || memoBy != null || commentedBy != null

    /** 배우자를 가리키는가 — 배우자가 아직 없는 갤러리에서는 거르지 않고 되묻는다. */
    fun refersToPartner(): Boolean = AgentActor.PARTNER in listOf(addedBy, ratedBy, memoBy, commentedBy)
}

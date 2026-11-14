package com.soma.wes.agent.dto

/** 사진 한 장에 남은 반응의 본문(#348 ③ 후속, 10-29). 거른 사진과 함께 화면에 싣는다 — LLM 은 읽지 않는다. */
data class PhotoReactionsDto(
    val photoId: Long,
    val rating: Int?,
    val memo: String?,
    /** 최근 것부터, 사진마다 [com.soma.wes.agent.repository.AgentReactionQueryRepository.MAX_COMMENTS_PER_PHOTO]개까지. */
    val comments: List<Comment>,
    /** 좋아요 누른 하객 이름표. */
    val likedBy: List<String>,
) {
    data class Comment(
        /** 부부 댓글은 계정 닉네임, 하객 댓글은 공유폴더 이름표. */
        val author: String,
        val content: String,
    )
}

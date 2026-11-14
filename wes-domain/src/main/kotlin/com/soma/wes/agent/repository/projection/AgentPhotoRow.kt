package com.soma.wes.agent.repository.projection

import com.soma.wes.agent.domain.AgentSubject
import com.soma.wes.agent.domain.PhotoOrientation
import java.time.LocalDateTime

/**
 * 도우미가 거르고 세는 사진 한 장. 폴더·피사체·방향·연사·선택 여부를 한 줄에 모았다.
 *
 * 반응(좋아요·댓글·별점·메모)은 사진마다 센 수다. 폴더가 없으면 미분류다. 분석이 덜 끝난 사진은 [subject]·[burstId]가 비어 그 조건에 걸리지 않는다.
 */
data class AgentPhotoRow(
    val photoId: Long,
    val conceptFolderId: Long?,
    val detailFolderId: Long?,
    val subject: AgentSubject?,
    val orientation: PhotoOrientation?,
    val burstId: Int?,
    val burstRank: Int?,
    val takenAt: LocalDateTime?,
    val selected: Boolean,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    /** 부부가 매긴 별점 1~5. 없으면 null. */
    val rating: Int? = null,
    val hasMemo: Boolean = false,
    /** 선택에 담은 사람. 담기지 않았거나 담은 사람을 모르면(관리자 이관 등) null. */
    val addedByUserId: Long? = null,
    /** 마지막으로 별점을 매긴 사람. */
    val ratedByUserId: Long? = null,
    /** 마지막으로 메모를 쓴 사람. */
    val memoByUserId: Long? = null,
    /** 부부 댓글을 단 사람들(지운 댓글 제외). */
    val commentAuthorIds: Set<Long> = emptySet(),
)

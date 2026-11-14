package com.soma.wes.agent.dto.response

import com.soma.wes.agent.domain.AgentActionStep
import com.soma.wes.agent.domain.AgentCheckCode
import com.soma.wes.agent.domain.AgentCheckSeverity
import com.soma.wes.agent.domain.AgentReplyKind
import com.soma.wes.agent.domain.AgentScreen
import com.soma.wes.agent.domain.ExplainTopic
import com.soma.wes.agent.domain.PhotoFilter
import com.soma.wes.agent.domain.StatsMetric
import com.soma.wes.agent.domain.StatsScope
import com.soma.wes.agent.domain.UnsupportedCondition
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "도우미의 답. 모든 종류가 같은 모양이고, kind에 맞는 칸만 찬다")
data class AgentMessageResponse(

    @field:Schema(description = "FIND_FOLDER·FILTER_PHOTOS·STATS·PLAN_ACTION·CLARIFY·EXPLAIN·CHECK")
    val kind: AgentReplyKind,

    @field:Schema(description = "화면에 보일 답 한 줄. 숫자는 서버가 센 값이다", example = "블랙수트에서 두 분이 함께 나온 사진 24장을 선택에 넣을게요.")
    val reply: String,

    @field:Schema(description = "화면을 이렇게 바꾼다 — 폴더 열기·필터. FIND_FOLDER·FILTER_PHOTOS·PLAN_ACTION, 점검 한 항목을 열 때 CHECK")
    val view: View? = null,

    @field:Schema(description = "폴더 후보가 여럿일 때 2~3개. FIND_FOLDER")
    val folderCandidates: List<FolderCandidate>? = null,

    @field:Schema(description = "센 결과. STATS")
    val stats: Stats? = null,

    @field:Schema(description = "확인하면 실행할 계획. [확인]을 누르면 steps를 그대로 실행 창구로 보낸다. PLAN_ACTION")
    val action: Action? = null,

    @field:Schema(description = "말에 있지만 지금은 못 거르는 조건. reply에서 못 한다고 말한다")
    val unsupported: List<UnsupportedCondition> = emptyList(),

    @field:Schema(description = "사용법·상태 답의 주제와 갈 수 있는 화면. EXPLAIN")
    val explain: Explain? = null,

    @field:Schema(description = "제출 전 점검 결과. CHECK. 항목 순서가 reply의 번호 순서다")
    val check: Check? = null,
) {

    @Schema(description = "제출 전 점검")
    data class Check(
        val selectedCount: Int,
        @field:Schema(description = "계약 장수. 없으면 null")
        val limit: Int? = null,
        @field:Schema(description = "걸린 항목만. 비어 있으면 걸리는 것이 없다")
        val items: List<CheckItem>,
    )

    @Schema(description = "점검 항목 하나")
    data class CheckItem(
        @field:Schema(description = "OVER_LIMIT·EMPTY_CONCEPT·BURST_DUPLICATE·SUBJECT_IMBALANCE·LOW_RATED_SELECTED")
        val code: AgentCheckCode,
        @field:Schema(description = "WARN = 제출을 막거나 빠뜨린 것, INFO = 참고. 웹이 아이콘을 고른다")
        val severity: AgentCheckSeverity,
        @field:Schema(description = "항목의 장수(EMPTY_CONCEPT은 컨셉 수, SUBJECT_IMBALANCE는 신부·신랑 단독 장수의 차)")
        val count: Int,
        @field:Schema(description = "\"그 사진 보기\" 대상 사진 id. BURST_DUPLICATE·LOW_RATED_SELECTED만, 고른 사진이라 계약 장수 안이다")
        val photoIds: List<Long> = emptyList(),
        @field:Schema(description = "\"그 폴더 보기\" 대상 컨셉 id. EMPTY_CONCEPT만")
        val conceptFolderIds: List<Long> = emptyList(),
    )

    @Schema(description = "사용법·상태 답")
    data class Explain(
        val topic: ExplainTopic,
        @field:Schema(description = "답 아래 버튼. 비어 있을 수 있다")
        val links: List<Link> = emptyList(),
    ) {
        data class Link(
            @field:Schema(example = "보정 화면으로")
            val label: String,
            val to: AgentScreen,
        )
    }

    @Schema(description = "바꿀 화면")
    data class View(
        @field:Schema(description = "열 컨셉 폴더 id. null이면 폴더를 바꾸지 않는다")
        val conceptFolderId: Long? = null,
        @field:Schema(description = "열 세부 폴더 id. null이면 컨셉 폴더 전체")
        val detailFolderId: Long? = null,
        @field:Schema(description = "필터 칩으로 보여 줄 조건. 사용자가 고치거나 끌 수 있다")
        val filter: PhotoFilter? = null,
        @field:Schema(description = "조건에 맞는 사진 id. 웹은 받아 둔 갤러리 사진에서 이 id만 보여 준다")
        val photoIds: List<Long>? = null,
        @field:Schema(description = "반응으로 거른 결과에만. 반응이 하나라도 있는 사진의 별점·메모·댓글·좋아요한 사람(앞에서부터 200장까지)")
        val reactions: List<PhotoReactions>? = null,
    )

    @Schema(description = "사진 한 장의 반응 본문")
    data class PhotoReactions(
        val photoId: Long,
        @field:Schema(description = "부부가 매긴 별점 1~5. 없으면 null")
        val rating: Int? = null,
        @field:Schema(description = "메모 본문. 없으면 null")
        val memo: String? = null,
        @field:Schema(description = "최근 것부터 3개까지. 부부 댓글은 닉네임, 하객 댓글은 공유폴더 이름표")
        val comments: List<Comment> = emptyList(),
        @field:Schema(description = "좋아요 누른 하객 이름표")
        val likedBy: List<String> = emptyList(),
    ) {
        data class Comment(
            val author: String,
            val content: String,
        )
    }

    @Schema(description = "열 수 있는 폴더 하나")
    data class FolderCandidate(
        val conceptFolderId: Long,
        @field:Schema(description = "세부 폴더 id. null이면 컨셉 폴더 자체가 후보")
        val detailFolderId: Long? = null,
        @field:Schema(example = "야외정원 > 노을")
        val name: String,
    )

    @Schema(description = "센 결과")
    data class Stats(
        val metric: StatsMetric,
        val scope: StatsScope,
        @field:Schema(description = "많은 것부터. BY_CONCEPT·BY_DETAIL은 0장인 폴더도 넣는다")
        val rows: List<Row>,
        val total: Int,
        @field:Schema(description = "계약 장수. 없으면 null")
        val limit: Int? = null,
    ) {
        data class Row(
            val label: String,
            val count: Int,
        )
    }

    @Schema(description = "실행 계획")
    data class Action(
        val steps: List<AgentActionStep>,
        @field:Schema(description = "실제로 바뀔 사진 수")
        val photoCount: Int,
        @field:Schema(description = "이미 그 상태라 뺀 사진 수(이미 고른 사진 등)")
        val skippedPhotoCount: Int,
        @field:Schema(description = "넣으면 계약 장수를 몇 장 넘는가. 넘지 않으면 0. 넘으면 실행이 거절된다")
        val overLimitBy: Int,
        @field:Schema(description = "큰 변경 — 조건 없이 전부이거나 바꿀 수 있는 사진의 대부분을 바꾼다. 확인 화면이 한 번 더 경고한다")
        val broad: Boolean = false,
    )
}

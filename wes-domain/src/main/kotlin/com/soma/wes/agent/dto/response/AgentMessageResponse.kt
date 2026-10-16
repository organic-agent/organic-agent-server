package com.soma.wes.agent.dto.response

import com.soma.wes.agent.domain.AgentActionStep
import com.soma.wes.agent.domain.AgentReplyKind
import com.soma.wes.agent.domain.PhotoFilter
import com.soma.wes.agent.domain.StatsMetric
import com.soma.wes.agent.domain.StatsScope
import com.soma.wes.agent.domain.UnsupportedCondition
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "도우미의 답. 모든 종류가 같은 모양이고, kind에 맞는 칸만 찬다")
data class AgentMessageResponse(

    @field:Schema(description = "FIND_FOLDER·FILTER_PHOTOS·STATS·PLAN_ACTION·CLARIFY")
    val kind: AgentReplyKind,

    @field:Schema(description = "화면에 보일 답 한 줄. 숫자는 서버가 센 값이다", example = "블랙수트에서 두 분이 함께 나온 사진 24장을 선택에 넣을게요.")
    val reply: String,

    @field:Schema(description = "화면을 이렇게 바꾼다 — 폴더 열기·필터. FIND_FOLDER·FILTER_PHOTOS·PLAN_ACTION")
    val view: View? = null,

    @field:Schema(description = "폴더 후보가 여럿일 때 2~3개. FIND_FOLDER")
    val folderCandidates: List<FolderCandidate>? = null,

    @field:Schema(description = "센 결과. STATS")
    val stats: Stats? = null,

    @field:Schema(description = "확인하면 실행할 계획. [확인]을 누르면 steps를 그대로 실행 창구로 보낸다. PLAN_ACTION")
    val action: Action? = null,

    @field:Schema(description = "말에 있지만 지금은 못 거르는 조건. reply에서 못 한다고 말한다")
    val unsupported: List<UnsupportedCondition> = emptyList(),
) {

    @Schema(description = "바꿀 화면")
    data class View(
        val conceptFolderId: Long? = null,
        val detailFolderId: Long? = null,
        @field:Schema(description = "필터 칩으로 보여 줄 조건. 사용자가 고치거나 끌 수 있다")
        val filter: PhotoFilter? = null,
        @field:Schema(description = "조건에 맞는 사진 id. 웹은 받아 둔 갤러리 사진에서 이 id만 보여 준다")
        val photoIds: List<Long>? = null,
    )

    @Schema(description = "열 수 있는 폴더 하나")
    data class FolderCandidate(
        val conceptFolderId: Long,
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
    )
}

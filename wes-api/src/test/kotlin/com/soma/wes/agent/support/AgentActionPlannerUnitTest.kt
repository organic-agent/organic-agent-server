package com.soma.wes.agent.support

import com.soma.wes.agent.domain.AgentActionOp
import com.soma.wes.agent.domain.AgentSubject
import com.soma.wes.agent.domain.PhotoFilter
import com.soma.wes.agent.dto.AgentDecisionDto
import com.soma.wes.agent.dto.AgentGallerySnapshotDto
import com.soma.wes.agent.repository.projection.AgentPhotoRow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 큰 변경 판정. 스냅숏만 보는 계산이라 DB 없이 돈다 — 통합 테스트 장면(9장)은 [AgentActionPlanner.BROAD_MIN_PHOTOS]보다 작다. */
class AgentActionPlannerUnitTest {

    private val planner = AgentActionPlanner()

    @Test
    fun `조건 없이 고른 사진을 전부 빼면 큰 변경이다`() {
        // given
        val snapshot = snapshot(selected = 30, unselected = 70)
        val decision = AgentDecisionDto.PlanAction(op = AgentActionOp.SELECT_REMOVE, filter = PhotoFilter(selected = true))

        // when
        val action = planner.plan(snapshot, decision, snapshot.photos)

        // then
        assertThat(action.broad).isTrue()
    }

    @Test
    fun `조건으로 좁혀도 바꿀 수 있는 사진의 대부분이면 큰 변경이다`() {
        // given
        val snapshot = snapshot(selected = 30, unselected = 0, subject = AgentSubject.COUPLE)
        val decision = AgentDecisionDto.PlanAction(
            op = AgentActionOp.SELECT_REMOVE,
            filter = PhotoFilter(subjects = listOf(AgentSubject.COUPLE)),
        )

        // when
        val action = planner.plan(snapshot, decision, snapshot.photos)

        // then
        assertThat(action.broad).isTrue()
    }

    @Test
    fun `조건으로 좁힌 일부만 바꾸면 큰 변경이 아니다`() {
        // given
        val snapshot = snapshot(selected = 30, unselected = 70)
        val decision = AgentDecisionDto.PlanAction(
            op = AgentActionOp.SELECT_REMOVE,
            filter = PhotoFilter(subjects = listOf(AgentSubject.COUPLE)),
        )
        val matched = snapshot.photos.filter { it.selected }.take(12)

        // when
        val action = planner.plan(snapshot, decision, matched)

        // then
        assertThat(action.broad).isFalse()
    }

    @Test
    fun `바꿀 사진이 몇 장 안 되면 전부여도 큰 변경이 아니다`() {
        // given
        val snapshot = snapshot(selected = 5, unselected = 0)
        val decision = AgentDecisionDto.PlanAction(op = AgentActionOp.SELECT_REMOVE, filter = PhotoFilter())

        // when
        val action = planner.plan(snapshot, decision, snapshot.photos)

        // then
        assertThat(action.broad).isFalse()
    }

    private fun snapshot(selected: Int, unselected: Int, subject: AgentSubject? = null): AgentGallerySnapshotDto {
        val photos = (1..selected + unselected).map { id ->
            AgentPhotoRow(
                photoId = id.toLong(),
                conceptFolderId = null,
                detailFolderId = null,
                subject = subject,
                orientation = null,
                burstId = null,
                burstRank = null,
                takenAt = null,
                selected = id <= selected,
            )
        }
        return AgentGallerySnapshotDto(
            galleryId = 1,
            maxSelectablePhotoCount = null,
            selectionEditable = true,
            concepts = emptyList(),
            photos = photos,
        )
    }
}

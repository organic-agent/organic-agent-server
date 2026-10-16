package com.soma.wes.agent.support

import com.soma.wes.agent.domain.AgentSubject
import com.soma.wes.agent.domain.PhotoOrientation
import com.soma.wes.agent.domain.StatsMetric
import com.soma.wes.agent.domain.StatsScope
import com.soma.wes.agent.dto.AgentDecisionDto
import com.soma.wes.agent.dto.AgentGallerySnapshotDto
import com.soma.wes.agent.dto.response.AgentMessageResponse
import com.soma.wes.agent.repository.projection.AgentPhotoRow
import org.springframework.stereotype.Component

/**
 * 센다. 숫자는 전부 여기서 나온다 — 판단기는 무엇으로 셀지만 정하고 숫자를 짓지 않는다.
 *
 * 폴더별로 셀 때는 0장인 폴더도 넣는다. "야외를 하나도 안 골랐다"가 균형 질문의 답이기 때문이다.
 */
@Component
class AgentStatsCounter(
    private val agentPhotoFinder: AgentPhotoFinder,
) {

    companion object {

        const val UNASSIGNED_LABEL = "미분류"
        const val UNKNOWN_LABEL = "알 수 없음"
        const val DUPLICATE_GROUPS_LABEL = "겹쳐 고른 연사 묶음"
        const val DUPLICATE_EXTRA_LABEL = "줄일 수 있는 사진"
    }

    fun count(snapshot: AgentGallerySnapshotDto, decision: AgentDecisionDto.Stats): AgentMessageResponse.Stats {
        val filter = when (decision.scope) {
            StatsScope.SELECTED -> decision.filter.copy(selected = true)
            StatsScope.GALLERY -> decision.filter
        }
        val photos = agentPhotoFinder.find(snapshot.photos, filter)

        val rows = when (decision.metric) {
            StatsMetric.COUNT -> emptyList()
            StatsMetric.BY_CONCEPT -> byConcept(snapshot, photos)
            StatsMetric.BY_DETAIL -> byDetail(snapshot, photos)
            StatsMetric.BY_SUBJECTS -> bySubjects(photos)
            StatsMetric.BY_ORIENTATION -> byOrientation(photos)
            StatsMetric.DUPLICATES -> duplicates(photos)
        }

        return AgentMessageResponse.Stats(
            metric = decision.metric,
            scope = decision.scope,
            rows = rows,
            total = photos.size,
            limit = snapshot.maxSelectablePhotoCount,
        )
    }

    private fun byConcept(snapshot: AgentGallerySnapshotDto, photos: List<AgentPhotoRow>): List<AgentMessageResponse.Stats.Row> {
        val countByConceptId = photos.groupingBy { it.conceptFolderId }.eachCount()
        val folders = snapshot.concepts.map { concept -> row(concept.name, countByConceptId[concept.id] ?: 0) }
        return sortedWithUnassigned(folders, countByConceptId[null] ?: 0)
    }

    private fun byDetail(snapshot: AgentGallerySnapshotDto, photos: List<AgentPhotoRow>): List<AgentMessageResponse.Stats.Row> {
        val countByDetailId = photos.groupingBy { it.detailFolderId }.eachCount()
        val folders = snapshot.concepts.flatMap { concept ->
            concept.details.map { detail -> row("${concept.name} > ${detail.name}", countByDetailId[detail.id] ?: 0) }
        }
        return sortedWithUnassigned(folders, countByDetailId[null] ?: 0)
    }

    private fun sortedWithUnassigned(
        folders: List<AgentMessageResponse.Stats.Row>,
        unassigned: Int,
    ): List<AgentMessageResponse.Stats.Row> {
        val sorted = folders.sortedByDescending { it.count }
        return if (unassigned > 0) sorted + row(UNASSIGNED_LABEL, unassigned) else sorted
    }

    private fun bySubjects(photos: List<AgentPhotoRow>): List<AgentMessageResponse.Stats.Row> {
        val countBySubject = photos.groupingBy { it.subject }.eachCount()
        val known = AgentSubject.entries.map { subject -> row(subject.label, countBySubject[subject] ?: 0) }
        val unknown = countBySubject[null] ?: 0
        return known.sortedByDescending { it.count } + listOfNotNull(row(UNKNOWN_LABEL, unknown).takeIf { unknown > 0 })
    }

    private fun byOrientation(photos: List<AgentPhotoRow>): List<AgentMessageResponse.Stats.Row> {
        val countByOrientation = photos.groupingBy { it.orientation }.eachCount()
        val known = PhotoOrientation.entries.map { orientation -> row(orientation.label, countByOrientation[orientation] ?: 0) }
        val unknown = countByOrientation[null] ?: 0
        return known.sortedByDescending { it.count } + listOfNotNull(row(UNKNOWN_LABEL, unknown).takeIf { unknown > 0 })
    }

    /** 같은 연사 묶음에서 두 장 이상 든 묶음 수와, 묶음마다 한 장만 남기면 줄어드는 장수. */
    private fun duplicates(photos: List<AgentPhotoRow>): List<AgentMessageResponse.Stats.Row> {
        val repeatedBursts = photos.mapNotNull { it.burstId }.groupingBy { it }.eachCount().values.filter { it > 1 }
        return listOf(
            row(DUPLICATE_GROUPS_LABEL, repeatedBursts.size),
            row(DUPLICATE_EXTRA_LABEL, repeatedBursts.sumOf { it - 1 }),
        )
    }

    private fun row(label: String, count: Int) = AgentMessageResponse.Stats.Row(label = label, count = count)
}

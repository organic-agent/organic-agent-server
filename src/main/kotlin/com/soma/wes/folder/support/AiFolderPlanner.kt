package com.soma.wes.category.support

import com.soma.wes.category.domain.CutType
import org.springframework.stereotype.Component

/** AI 그룹 배정과 사진 분석을 Concept → Detail → Photo 단일 배정 계획으로 바꾼다. */
@Component
class AiCategoryFolderPlanner {

    fun plan(assignments: List<GroupAssignment>, members: List<MemberPhoto>): List<ConceptPlan> {
        if (members.isEmpty()) return emptyList()
        val assignmentByGroup = assignments.associateBy { it.embedGroupId }
        val byDetail = members.groupBy { member ->
            val assignment = member.embedGroupId?.let { assignmentByGroup[it] }
            DetailKey(
                conceptName = assignment?.parentName ?: ETC_NAME,
                detailName = assignment?.conceptName ?: ETC_NAME,
            )
        }

        return byDetail.entries
            .groupBy({ it.key.conceptName }, { it })
            .map { (conceptName, details) -> planConcept(conceptName, details, assignmentByGroup) }
            .sortedWith(
                compareBy<ConceptPlan> { it.name == ETC_NAME }
                    .thenByDescending { concept -> concept.details.sumOf { it.photoIds.size } },
            )
    }

    private fun planConcept(
        conceptName: String,
        details: List<Map.Entry<DetailKey, List<MemberPhoto>>>,
        assignmentByGroup: Map<Int, GroupAssignment>,
    ): ConceptPlan {
        val detailPlans = details.map { (key, detailMembers) ->
            val sorted = detailMembers.sortedWith(MEMBER_ORDER)
            DetailPlan(
                name = key.detailName,
                cutType = majorityCutType(sorted),
                needsReview = sorted.any { member ->
                    member.embedGroupId?.let { assignmentByGroup[it] }?.needsReview ?: false
                },
                photoIds = sorted.map { it.photoId },
            )
        }.sortedWith(
            compareBy<DetailPlan> { it.name == ETC_NAME }
                .thenByDescending { it.photoIds.size },
        )
        return ConceptPlan(conceptName, detailPlans)
    }

    private fun majorityCutType(members: List<MemberPhoto>): CutType? {
        val counts = members.mapNotNull { CutType.fromSubjects(it.subjects) }
            .groupingBy { it }
            .eachCount()
        val (cutType, count) = counts.maxByOrNull { it.value } ?: return null
        return cutType.takeIf { count * 2 > members.size }
    }

    private data class DetailKey(val conceptName: String, val detailName: String)

    data class GroupAssignment(
        val embedGroupId: Int,
        val parentName: String,
        val conceptName: String,
        val needsReview: Boolean,
    )

    data class MemberPhoto(
        val photoId: Long,
        val embedGroupId: Int?,
        val subjects: String?,
        val clusterId: Int?,
    )

    data class ConceptPlan(val name: String, val details: List<DetailPlan>)

    data class DetailPlan(
        val name: String,
        val cutType: CutType?,
        val needsReview: Boolean,
        val photoIds: List<Long>,
    )

    companion object {
        const val ETC_NAME = "기타"
        private val MEMBER_ORDER = compareBy<MemberPhoto>(
            { it.embedGroupId ?: Int.MAX_VALUE },
            { it.clusterId ?: Int.MAX_VALUE },
        )
    }
}

package com.soma.wes.folder.support

import com.soma.wes.folder.domain.CutType
import com.soma.wes.folder.dto.ConceptPlanDto
import com.soma.wes.folder.dto.DetailPlanDto
import com.soma.wes.folder.dto.GroupAssignmentDto
import com.soma.wes.folder.dto.MemberPhotoDto
import org.springframework.stereotype.Component

// [REFACTOR-PLANNER-DTO 2026-09-27] 중첩 data class 4개(GroupAssignment·MemberPhoto·ConceptPlan·DetailPlan)를 folder/dto의 ~Dto로 옮겼다.
// private DetailKey는 없애고 컨셉 → 세부 두 단계 groupBy로 풀었다(groupBy는 첫 등장 순서를 지키고 뒤에서 정렬하므로 결과 동일).
/** AI 그룹 배정과 사진 분석을 Concept → Detail → Photo 단일 배정 계획으로 바꾼다. */
@Component
class AiFolderPlanner {

    companion object {
        const val ETC_NAME = "기타"
        private val MEMBER_ORDER = compareBy<MemberPhotoDto>(
            { it.embedGroupId ?: Int.MAX_VALUE },
            { it.clusterId ?: Int.MAX_VALUE },
        )
    }

    fun plan(
        assignments: List<GroupAssignmentDto>,
        members: List<MemberPhotoDto>,
    ): List<ConceptPlanDto> {
        if (members.isEmpty()) return emptyList()
        val assignmentByGroup = assignments.associateBy { it.embedGroupId }
        fun assignmentOf(member: MemberPhotoDto) = member.embedGroupId?.let { assignmentByGroup[it] }

        // [REFACTOR-PLANNER-DTO 2026-09-27] DetailKey(컨셉, 세부)로 한 번에 묶던 것을 컨셉 → 세부 두 단계로 묶는다.
        return members
            .groupBy { assignmentOf(it)?.conceptName ?: ETC_NAME }
            .map { (conceptName, conceptMembers) ->
                val detailMembers = conceptMembers.groupBy { assignmentOf(it)?.detailName ?: ETC_NAME }
                planConcept(conceptName, detailMembers, assignmentByGroup)
            }
            .sortedWith(
                compareBy<ConceptPlanDto> { it.name == ETC_NAME }
                    .thenByDescending { concept -> concept.details.sumOf { it.photoIds.size } },
            )
    }

    private fun planConcept(
        conceptName: String,
        membersByDetail: Map<String, List<MemberPhotoDto>>,
        assignmentByGroup: Map<Int, GroupAssignmentDto>,
    ): ConceptPlanDto {
        val detailPlans = membersByDetail.map { (detailName, detailMembers) ->
            val sorted = detailMembers.sortedWith(MEMBER_ORDER)
            DetailPlanDto(
                name = detailName,
                cutType = majorityCutType(sorted),
                needsReview = sorted.any { member ->
                    member.embedGroupId?.let { assignmentByGroup[it] }?.needsReview ?: false
                },
                photoIds = sorted.map { it.photoId },
            )
        }.sortedWith(
            compareBy<DetailPlanDto> { it.name == ETC_NAME }
                .thenByDescending { it.photoIds.size },
        )
        return ConceptPlanDto(name = conceptName, details = detailPlans)
    }

    private fun majorityCutType(members: List<MemberPhotoDto>): CutType? {
        val counts = members.mapNotNull { CutType.fromSubjects(it.subjects) }
            .groupingBy { it }
            .eachCount()
        val (cutType, count) = counts.maxByOrNull { it.value } ?: return null
        return cutType.takeIf { count * 2 > members.size }
    }
}

package com.soma.wes.folder.support

import com.soma.wes.analysis.dto.ConceptAssignmentDto
import com.soma.wes.folder.domain.CutType
import com.soma.wes.folder.dto.ConceptFolderPlanDto
import com.soma.wes.folder.dto.DetailFolderPlanDto
import com.soma.wes.folder.dto.PhotoAnalysisGroupingDto
import org.springframework.stereotype.Component


/** AI 그룹 배정과 사진 분석을 Concept → Detail → Photo 단일 배정 계획으로 바꾼다. */
@Component
class AiFolderPlanner {

    companion object {
        const val ETC_NAME = "기타"
        private val PHOTO_ORDER = compareBy<PhotoAnalysisGroupingDto>(
            { it.embedGroupId ?: Int.MAX_VALUE },
            { it.burstId ?: Int.MAX_VALUE },
        )
    }

    fun plan(
        assignments: List<ConceptAssignmentDto>,
        photos: List<PhotoAnalysisGroupingDto>,
    ): List<ConceptFolderPlanDto> {
        if (photos.isEmpty()) return emptyList()
        val assignmentByEmbedGroup = assignments.associateBy { it.embedGroupId }
        fun assignmentOf(photo: PhotoAnalysisGroupingDto) = photo.embedGroupId?.let { assignmentByEmbedGroup[it] }

        // [REFACTOR-PLANNER-DTO 2026-09-27] DetailKey(컨셉, 세부)로 한 번에 묶던 것을 컨셉 → 세부 두 단계로 묶는다.
        return photos
            .groupBy { assignmentOf(it)?.conceptName ?: ETC_NAME }
            .map { (conceptName, conceptPhotos) ->
                val photosByDetail = conceptPhotos.groupBy { assignmentOf(it)?.detailName ?: ETC_NAME }
                planConcept(conceptName, photosByDetail, assignmentByEmbedGroup)
            }
            .sortedWith(
                compareBy<ConceptFolderPlanDto> { it.name == ETC_NAME }
                    .thenByDescending { concept -> concept.details.sumOf { it.photoIds.size } },
            )
    }

    private fun planConcept(
        conceptName: String,
        photosByDetail: Map<String, List<PhotoAnalysisGroupingDto>>,
        assignmentByEmbedGroup: Map<Int, ConceptAssignmentDto>,
    ): ConceptFolderPlanDto {
        val detailPlans = photosByDetail.map { (detailName, detailPhotos) ->
            val sorted = detailPhotos.sortedWith(PHOTO_ORDER)
            DetailFolderPlanDto(
                name = detailName,
                cutType = majorityCutType(sorted),
                needsReview = sorted.any { photo ->
                    photo.embedGroupId?.let { assignmentByEmbedGroup[it] }?.needsReview ?: false
                },
                photoIds = sorted.map { it.photoId },
            )
        }.sortedWith(
            compareBy<DetailFolderPlanDto> { it.name == ETC_NAME }
                .thenByDescending { it.photoIds.size },
        )
        return ConceptFolderPlanDto(name = conceptName, details = detailPlans)
    }

    private fun majorityCutType(photos: List<PhotoAnalysisGroupingDto>): CutType? {
        // {신부 = 5, 남편 = 1 두 분 =2} 과 같은 규격으로 생성
        val counts = photos.mapNotNull { CutType.fromSubjects(it.subjects) }
            .groupingBy { it }
            .eachCount()
        // 가장 많은 종류와 그 장수를 꺼낸다. 동점이면 먼저 나온 종류가 골라짐.
        val (cutType, count) = counts.maxByOrNull { it.value } ?: return null
        // 그 종류가 전체 사진 수의 절반을 넘을 때만 리턴
        return cutType.takeIf { count * 2 > photos.size }
    }
}

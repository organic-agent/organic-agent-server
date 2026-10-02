package com.soma.wes.folder.support

import com.soma.wes.analysis.dto.ConceptAssignmentDto
import com.soma.wes.folder.dto.ConceptFolderPlanDto
import com.soma.wes.folder.dto.DetailFolderPlanDto
import com.soma.wes.folder.dto.FolderPlanDto
import com.soma.wes.folder.dto.PhotoAnalysisGroupingDto
import org.springframework.stereotype.Component

/**
 * AI 그룹 배정과 사진 분석을 폴더 계획으로 바꾼다. 이미 폴더에 든 사진은 그대로 두고, 아직 폴더에 없는 사진의 자리만 정한다.
 *
 * 새 분석은 갤러리 전체를 다시 묶으므로 한 그룹 안에 "이미 폴더에 든 옛 사진"과 "새 사진"이 같이 있다. AI 가 붙이는 이름은
 * 실행마다 달라("야외 공원"/"야외 정원") 믿지 않고, **같은 그룹의 옛 사진이 지금 어느 폴더에 있는지**로 자리를 정한다.
 * 새 사진이 든 그룹마다 위에서부터 처음 맞는 것 하나:
 * 1. 그룹의 옛 사진 과반이 한 세부 폴더에 있다 → 그 세부 폴더
 * 2. 이번 분석이 같은 세부 이름으로 묶은 그룹들의 옛 사진 과반이 한 세부 폴더에 있다 → 그 세부 폴더
 * 3. 그룹이 속한 컨셉의 옛 사진 과반이 한 컨셉 폴더에 있다 → 그 컨셉 폴더 아래 새 세부 폴더
 * 4. 같은 이름의 컨셉 폴더가 있다 → 그 컨셉 폴더 아래 새 세부 폴더
 * 5. 그 밖 → 새 컨셉 폴더 + 새 세부 폴더
 *
 * 2번이 없으면 한 장면이 폴더 둘로 갈린다 — 한 세부 이름은 그룹 여럿이라, 옛 사진이 든 그룹만 기존 폴더("흰벽 전신")로 가고
 * 새 사진뿐인 그룹은 같은 컨셉 아래 새 폴더("흰 벽 전신")가 된다. 다만 옛 사진이 그 세부의 극히 일부면(367장 중 8장) 근거가
 * 약하므로, 옛 사진이 일정 비율([minOldShareForDetailMerge]) 이상일 때만 2번을 쓴다.
 *
 * 옛 사진에는 사용자가 직접 옮긴 사진도 들어 있다 — 사용자가 고친 구조를 새 사진이 따라간다.
 * 3~5번에서 새 세부 폴더가 최소 장수에 못 미치면 만들지 않고 그 사진을 미분류로 남긴다. 처음 분석(폴더에 든 사진이 하나도 없다)은
 * 전부 5번이고 최소 장수도 걸지 않아, 합치기가 생기기 전과 결과가 같다.
 */
@Component
class AiFolderPlanner {

    companion object {
        const val ETC_NAME = "기타"
        private val PHOTO_ORDER = compareBy<PhotoAnalysisGroupingDto>(
            { it.embedGroupId ?: Int.MAX_VALUE },
            { it.burstId ?: Int.MAX_VALUE },
        )
    }

    /**
     * @param photos 분류가 끝난 사진 전부(옛 + 새)
     * @param detailFolderIdByPhotoId 이미 폴더에 든 사진 → 그 세부 폴더
     * @param conceptFolderIdByDetailFolderId 기존 세부 폴더 → 그 컨셉 폴더
     * @param conceptFolderIdByName 기존 컨셉 폴더 이름 → id (같은 이름이 여럿이면 화면 순서상 앞의 것)
     * @param minNewDetailPhotos 이미 폴더가 있는 갤러리에 새 세부 폴더를 만드는 최소 장수
     * @param minOldShareForDetailMerge 2번 규칙을 쓰려면 그 세부 이름의 사진 중 옛 사진이 차지해야 하는 최소 비율
     */
    fun plan(
        assignments: List<ConceptAssignmentDto>,
        photos: List<PhotoAnalysisGroupingDto>,
        detailFolderIdByPhotoId: Map<Long, Long> = emptyMap(),
        conceptFolderIdByDetailFolderId: Map<Long, Long> = emptyMap(),
        conceptFolderIdByName: Map<String, Long> = emptyMap(),
        minNewDetailPhotos: Int = 1,
        minOldShareForDetailMerge: Double = 0.0,
    ): FolderPlanDto {
        val (oldPhotos, newPhotos) = photos.partition { it.photoId in detailFolderIdByPhotoId }
        if (newPhotos.isEmpty()) return FolderPlanDto(emptyMap(), emptyMap(), emptyList(), emptyList())

        val assignmentByEmbedGroup = assignments.associateBy { it.embedGroupId }
        fun assignmentOf(embedGroupId: Int?) = embedGroupId?.let { assignmentByEmbedGroup[it] }
        fun conceptNameOf(embedGroupId: Int?) = assignmentOf(embedGroupId)?.conceptName ?: ETC_NAME

        // 옛 사진의 표 — 그룹마다 "어느 세부 폴더에", 컨셉마다 "어느 컨셉 폴더에".
        val oldDetailFolderIdsByGroup = oldPhotos.groupBy({ it.embedGroupId }, { detailFolderIdByPhotoId.getValue(it.photoId) })
        val majorityConceptFolderIdByConceptName = oldPhotos
            .groupBy(
                { conceptNameOf(it.embedGroupId) },
                { conceptFolderIdByDetailFolderId[detailFolderIdByPhotoId.getValue(it.photoId)] },
            )
            .mapValues { (_, conceptFolderIds) -> majorityOf(conceptFolderIds.filterNotNull()) }

        // 2번 규칙의 표 — 이번 분석의 (컨셉, 세부 이름)마다 옛 사진이 과반 든 세부 폴더. 옛 사진 비율이 낮은 세부는 뺀다.
        fun detailKeyOf(embedGroupId: Int?) = conceptNameOf(embedGroupId) to (assignmentOf(embedGroupId)?.detailName ?: ETC_NAME)
        val photoCountByDetailKey = photos.groupingBy { detailKeyOf(it.embedGroupId) }.eachCount()
        val majorityDetailFolderIdByDetailKey = oldPhotos
            .groupBy({ detailKeyOf(it.embedGroupId) }, { detailFolderIdByPhotoId.getValue(it.photoId) })
            .filter { (key, oldDetailFolderIds) -> oldDetailFolderIds.size >= photoCountByDetailKey.getValue(key) * minOldShareForDetailMerge }
            .mapValues { (_, oldDetailFolderIds) -> majorityOf(oldDetailFolderIds) }

        val merges = mutableMapOf<Long, MutableList<PhotoAnalysisGroupingDto>>()
        val underExistingConcept = mutableMapOf<Pair<Long, String>, MutableList<PhotoAnalysisGroupingDto>>()
        val underNewConcept = mutableMapOf<Pair<String, String>, MutableList<PhotoAnalysisGroupingDto>>()
        for ((embedGroupId, groupPhotos) in newPhotos.groupBy { it.embedGroupId }) {
            val majorityDetailFolderId = majorityOf(oldDetailFolderIdsByGroup[embedGroupId].orEmpty())
                ?: majorityDetailFolderIdByDetailKey[detailKeyOf(embedGroupId)]
            if (majorityDetailFolderId != null) {
                merges.getOrPut(majorityDetailFolderId) { mutableListOf() } += groupPhotos
                continue
            }
            val conceptName = conceptNameOf(embedGroupId)
            val detailName = assignmentOf(embedGroupId)?.detailName ?: ETC_NAME
            val conceptFolderId = majorityConceptFolderIdByConceptName[conceptName] ?: conceptFolderIdByName[conceptName]
            if (conceptFolderId != null) {
                underExistingConcept.getOrPut(conceptFolderId to detailName) { mutableListOf() } += groupPhotos
            } else {
                underNewConcept.getOrPut(conceptName to detailName) { mutableListOf() } += groupPhotos
            }
        }

        // 처음 분석에는 최소 장수를 걸지 않는다 — 폴더에 든 사진이 하나도 없으면 합칠 곳도, 자투리 폴더가 "늘어날" 일도 없다.
        val minPhotos = if (detailFolderIdByPhotoId.isEmpty()) 1 else minNewDetailPhotos
        val (keptUnderExisting, droppedUnderExisting) = underExistingConcept.entries.partition { it.value.size >= minPhotos }
        val (keptUnderNew, droppedUnderNew) = underNewConcept.entries.partition { it.value.size >= minPhotos }
        fun detailPlanOf(detailName: String, detailPhotos: List<PhotoAnalysisGroupingDto>): DetailFolderPlanDto {
            val sorted = detailPhotos.sortedWith(PHOTO_ORDER)
            return DetailFolderPlanDto(
                name = detailName,
                needsReview = sorted.any { assignmentOf(it.embedGroupId)?.needsReview ?: false },
                photoIds = sorted.map { it.photoId },
            )
        }

        return FolderPlanDto(
            merges = merges.mapValues { (_, mergedPhotos) -> mergedPhotos.sortedWith(PHOTO_ORDER).map { it.photoId } },
            newDetails = keptUnderExisting
                .groupBy({ (key, _) -> key.first }, { (key, detailPhotos) -> detailPlanOf(key.second, detailPhotos) })
                .mapValues { (_, details) -> details.sortedWith(DETAIL_ORDER) },
            newConcepts = keptUnderNew
                .groupBy({ (key, _) -> key.first }, { (key, detailPhotos) -> detailPlanOf(key.second, detailPhotos) })
                .map { (conceptName, details) -> ConceptFolderPlanDto(name = conceptName, details = details.sortedWith(DETAIL_ORDER)) }
                .sortedWith(
                    compareBy<ConceptFolderPlanDto> { it.name == ETC_NAME }
                        .thenByDescending { concept -> concept.details.sumOf { it.photoIds.size } },
                ),
            leftUnclassified = (droppedUnderExisting + droppedUnderNew).flatMap { (_, detailPhotos) -> detailPhotos.map { it.photoId } },
        )
    }

    /** 절반을 넘게 차지한 값. 없으면(비었거나 갈렸으면) null — 옛 사진이 흩어진 그룹을 한쪽으로 쏠리게 하지 않는다. */
    private fun <T : Any> majorityOf(values: List<T>): T? {
        val (value, count) = values.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        return value.takeIf { count * 2 > values.size }
    }
}

/** "기타"는 맨 뒤, 나머지는 사진이 많은 순. */
private val DETAIL_ORDER = compareBy<DetailFolderPlanDto> { it.name == AiFolderPlanner.ETC_NAME }
    .thenByDescending { it.photoIds.size }

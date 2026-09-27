package com.soma.wes.analysis.support

import com.soma.wes.analysis.dto.ConceptAssignmentDto
import com.soma.wes.analysis.dto.LatestConceptAssignmentsDto
import com.soma.wes.analysis.repository.ConceptAssignmentRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 다른 도메인(folder)이 컨셉 배정을 읽는 입구. repository를 직접 주입하는 대신 이 로더를 지난다.
 */
@Component
class ConceptAssignmentLoader(
    private val conceptAssignmentRepository: ConceptAssignmentRepository,
) {

    /**
     * 갤러리에서 가장 최근에 배정을 남긴 잡의 배정 전부. 배정이 없으면(naming이 돈 적 없음) null —
     * 호출자(폴더 생성)가 409로 번역한다. 배정은 naming이 끝날 때 한 번에 쌓이므로, 행이 있다는
     * 것이 곧 그 잡의 이름 붙이기가 완료됐다는 뜻이다.
     */
    @Transactional(readOnly = true)
    fun loadLatest(galleryId: Long): LatestConceptAssignmentsDto? {
        val assignments = conceptAssignmentRepository.findAllOfLatestJobByGalleryId(galleryId)
        if (assignments.isEmpty()) return null
        return LatestConceptAssignmentsDto(
            analysisJobId = assignments.first().jobId,
            assignments = assignments.map {
                ConceptAssignmentDto(
                    embedGroupId = it.embedGroupId,
                    conceptName = it.conceptName,
                    detailName = it.detailName,
                    needsReview = it.needsReview,
                )
            },
        )
    }
}

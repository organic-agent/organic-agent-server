package com.soma.wes.folder.support

import com.soma.wes.folder.domain.ConceptFolder
import com.soma.wes.folder.dto.response.ConceptFolderResponse
import com.soma.wes.folder.dto.response.DetailFolderResponse
import com.soma.wes.folder.repository.DetailFolderRepository
import com.soma.wes.folder.repository.DetailFolderAssignmentRepository
import org.springframework.stereotype.Component

// [REFACTOR-A 2026-09-27] 신규. FolderService.list 본문과 AiFolderService.responsesOf가 같은 조립을 한 벌씩 들고 있던 것을
// 여기로 모았다. 두 곳의 private detailResponse(...)는 DetailFolderResponse.of로 대체.
/**
 * 컨셉 폴더를 세부 폴더·사진 id까지 채운 응답으로 만든다. 세부 폴더는 `sortOrder, id` 순서다.
 *
 * 방금 만든 AI 세트처럼 엔티티와 사진 id가 이미 손에 있는 경로는 이 클래스를 거치지 않고 메모리에서 조립한다 —
 * 수천 행을 다시 읽지 않기 위해서다(#160, `AiFolderMaterializeService`).
 */
@Component
class FolderViewAssembler(
    private val detailRepository: DetailFolderRepository,
    private val assignmentRepository: DetailFolderAssignmentRepository,
) {

    /** 컨셉 수와 무관하게 쿼리 2번(세부 폴더 IN, 배정 IN). 목록은 반드시 이것으로 조립한다. */
    fun toResponses(concepts: List<ConceptFolder>): List<ConceptFolderResponse> {
        // [REFACTOR-A 2026-09-27] 빈 목록이면 IN 쿼리 둘을 보내지 않는다. 결과는 이전과 같다(빈 리스트).
        if (concepts.isEmpty()) return emptyList()
        val details = detailRepository.findAllByConceptFolderIdIn(concepts.map { it.requiredId })
        val photoIdsByDetail = assignmentRepository.findAllByDetailFolderIdIn(details.map { it.requiredId })
            .groupBy({ it.detailFolderId }, { it.photoId })
        val detailsByConcept = details.groupBy { it.conceptFolderId }
        return concepts.map { concept ->
            ConceptFolderResponse.of(
                concept,
                detailsByConcept[concept.requiredId].orEmpty()
                    .sortedWith(compareBy({ it.sortOrder }, { it.requiredId }))
                    .map { DetailFolderResponse.of(it, photoIdsByDetail[it.requiredId].orEmpty()) },
            )
        }
    }
}

package com.soma.wes.agent.support

import com.soma.wes.agent.dto.AgentGallerySnapshotDto
import com.soma.wes.agent.repository.AgentPhotoQueryRepository
import com.soma.wes.folder.repository.ConceptFolderRepository
import com.soma.wes.folder.repository.DetailFolderRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireById
import com.soma.wes.selection.repository.PhotoSelectionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 판단 전에 갤러리를 한 번 읽는다. 판단은 이 트랜잭션 밖에서 기다린다 — Bedrock을 기다리며 커넥션을 물지 않는다. */
@Service
class AgentGalleryLoader(
    private val galleryRepository: GalleryRepository,
    private val photoSelectionRepository: PhotoSelectionRepository,
    private val conceptFolderRepository: ConceptFolderRepository,
    private val detailFolderRepository: DetailFolderRepository,
    private val agentPhotoQueryRepository: AgentPhotoQueryRepository,
) {

    @Transactional(readOnly = true)
    fun load(galleryId: Long): AgentGallerySnapshotDto {
        val gallery = galleryRepository.requireById(galleryId)
        val selection = photoSelectionRepository.findByGalleryId(galleryId)

        val concepts = conceptFolderRepository.findAllByGalleryIdOrderBySortOrderAscIdAsc(galleryId)
        val detailsByConceptId = detailFolderRepository.findAllByConceptFolderIdIn(concepts.map { it.requiredId })
            .sortedWith(compareBy({ it.sortOrder }, { it.requiredId }))
            .groupBy { it.conceptFolderId }

        return AgentGallerySnapshotDto(
            galleryId = galleryId,
            maxSelectablePhotoCount = gallery.maxSelectablePhotoCount,
            selectionEditable = selection?.isSubmitted != true,
            concepts = concepts.map { concept ->
                AgentGallerySnapshotDto.Concept(
                    id = concept.requiredId,
                    name = concept.name,
                    details = detailsByConceptId[concept.requiredId].orEmpty().map { detail ->
                        AgentGallerySnapshotDto.Detail(id = detail.requiredId, name = detail.name)
                    },
                )
            },
            photos = agentPhotoQueryRepository.findRows(galleryId),
        )
    }
}

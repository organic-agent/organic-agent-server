package com.soma.wes.folder.fixture

import com.soma.wes.folder.domain.ConceptFolder
import com.soma.wes.folder.domain.DetailFolder
import com.soma.wes.folder.domain.DetailFolderAssignment
import com.soma.wes.folder.domain.FolderSource
import com.soma.wes.folder.repository.ConceptFolderRepository
import com.soma.wes.folder.repository.DetailFolderAssignmentRepository
import com.soma.wes.folder.repository.DetailFolderRepository
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

@Component
class FolderFixture(
    private val conceptFolderRepository: ConceptFolderRepository,
    private val detailFolderRepository: DetailFolderRepository,
    private val assignmentRepository: DetailFolderAssignmentRepository,
) {

    /** AI가 만든 컨셉 폴더. 정렬 순서는 만든 순서다. */
    fun 컨셉(galleryId: Long, name: String, sortOrder: Int = 0): Long =
        conceptFolderRepository.save(
            ConceptFolder(galleryId = galleryId, name = name, sortOrder = sortOrder, createdSource = FolderSource.AI),
        ).requiredId

    fun 세부(galleryId: Long, conceptId: Long, name: String, sortOrder: Int = 0): Long =
        detailFolderRepository.save(
            DetailFolder(
                galleryId = galleryId,
                conceptFolderId = conceptId,
                name = name,
                sortOrder = sortOrder,
                createdSource = FolderSource.AI,
            ),
        ).requiredId

    /** AI 물질화가 사진을 세부 폴더에 배정한 상태. 이동 검사를 지나지 않으므로 배경으로만 쓴다. */
    fun 배정(galleryId: Long, detailId: Long, photoIds: Collection<Long>) {
        assignmentRepository.saveAll(
            photoIds.map { photoId ->
                DetailFolderAssignment(
                    galleryId = galleryId,
                    photoId = photoId,
                    detailFolderId = detailId,
                    assignedByUserId = null,
                    assignedSource = FolderSource.AI,
                    confidence = null,
                    assignedAt = ZonedDateTime.now(),
                )
            },
        )
    }
}

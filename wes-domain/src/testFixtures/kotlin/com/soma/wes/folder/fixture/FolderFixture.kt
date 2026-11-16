package com.soma.wes.folder.fixture

import com.soma.wes.folder.domain.ConceptFolder
import com.soma.wes.folder.domain.DetailFolder
import com.soma.wes.folder.domain.DetailFolderAssignment
import com.soma.wes.folder.domain.FolderSource
import com.soma.wes.folder.repository.ConceptFolderRepository
import com.soma.wes.folder.repository.DetailFolderAssignmentRepository
import com.soma.wes.folder.repository.DetailFolderRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 폴더 장면을 만든다. 컨셉 폴더 삭제는 trash 도메인의 SQL이 하는 일이라 엔티티에 쓰는 메서드가 없어 SQL로 둔다.
 * 컨셉·세부·배정은 AI 물질화가 만든 상태를 흉내 낸다 — 이동 검사를 지나지 않으므로 배경으로만 쓴다.
 */
@Component
class FolderFixture(
    private val jdbcTemplate: JdbcTemplate,
    private val conceptFolderRepository: ConceptFolderRepository,
    private val detailFolderRepository: DetailFolderRepository,
    private val assignmentRepository: DetailFolderAssignmentRepository,
    private val clock: Clock,
) {

    fun 컨셉_삭제됨(conceptId: Long, at: ZonedDateTime) {
        jdbcTemplate.update(
            "UPDATE concept_folders SET deleted_at = ?, version = version + 1, updated_at = now() WHERE id = ?",
            at.toOffsetDateTime(), conceptId,
        )
    }

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
                    assignedAt = ZonedDateTime.now(clock),
                )
            },
        )
    }
}

package com.soma.wes.folder.repository

import com.soma.wes.folder.domain.FolderSource
import java.sql.Timestamp
import java.time.ZonedDateTime
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

// [GLOSSARY-1 2026-09-27] PhotoFolderAssignmentBulkRepository → DetailFolderAssignmentBulkRepository (용어집 D9)
/**
 * AI 폴더 실체화가 만드는 수천 행의 대량 적재. `photo_category_assignments`(PK = photo_id)는 id를 앱이 정하는 엔티티라
 * Spring Data `saveAll`이 행마다 존재 확인 SELECT + INSERT 두 왕복을 한다 — 7천 장이면 3만 번 가까운 RDS 왕복으로 100초가 걸렸다(#160).
 * 여기서는 JDBC 배치로 한 번에 보낸다. 엔티티는 조회와 단건 갱신에만 쓴다.
 *
 * 호출자의 트랜잭션 안에서만 돈다(MANDATORY) — 폴더 INSERT와 같은 트랜잭션이어야 부분 실패가 남지 않는다.
 * JPA 영속성 컨텍스트를 지나지 않으므로 같은 트랜잭션에서 방금 넣은 행을 엔티티로 다시 읽을 일이 있으면
 * 조회는 DB로 간다(1차 캐시에 없다).
 */
@Repository
class DetailFolderAssignmentBulkRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    /** 한 세부 폴더에 AI 배정으로 사진들을 넣는다. 이미 배정된 사진은 호출자가 걸러 둔다(PK 충돌은 예외로 올라온다). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun insertAiAssignments(
        galleryId: Long,
        detailFolderId: Long,
        photoIds: List<Long>,
        assignedAt: ZonedDateTime
    ) {
        if (photoIds.isEmpty()) return
        val at = Timestamp.from(assignedAt.toInstant())
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO photo_category_assignments
                (gallery_id, photo_id, detail_folder_id, assigned_by_user_id, assigned_source, confidence, assigned_at, version, created_at, updated_at)
            VALUES (?, ?, ?, NULL, ?, NULL, ?, 0, ?, ?)
            """.trimIndent(),
            photoIds.map { photoId -> arrayOf<Any>(galleryId, photoId, detailFolderId, FolderSource.AI.name, at, at, at) },
        )
    }
}

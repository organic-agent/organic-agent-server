package com.soma.wes.photo.repository

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 휴지통에 든 사진의 지문을 다룬다. 살아 있는 사진의 지문은 JPA([PhotoRepository.findAllByGalleryIdAndSourceHashIn])가 읽고,
 * 여기는 `@SQLRestriction`이 가리는 행만 본다.
 *
 * 휴지통 행의 네이티브 SQL 은 `trash` 도메인의 `TrashRepository`에 모으는 것이 원칙이지만, `trash`가 `photo`에 기대고 있어
 * 반대로 기댈 수 없다. 그래서 지문 두 문장만 여기 둔다 — 휴지통 화면·복원·물리 삭제는 여전히 `trash`의 것이다.
 */
@Repository
class PhotoSourceHashRepository(
    private val jdbcClient: JdbcClient,
) {

    /** [sourceHashes] 중 이 갤러리의 휴지통 사진이 가진 지문. "지운 사진인데 다시 올릴까요"를 묻는 근거다. */
    fun findTrashed(galleryId: Long, sourceHashes: Collection<String>): Set<String> {
        if (sourceHashes.isEmpty()) return emptySet()
        return jdbcClient.sql(
            """
            SELECT DISTINCT source_hash
            FROM photos
            WHERE gallery_id = :galleryId AND deleted_at IS NOT NULL AND source_hash IN (:sourceHashes)
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .param("sourceHashes", sourceHashes.toList())
            .query { rs, _ -> rs.getString(1) }
            .set()
    }

    /**
     * 같은 원본이 새로 올라올 때 휴지통 사진의 지문을 비운다. 살아 있는 사진의 지문은 갤러리에서 하나뿐이라, 비우지 않으면
     * 그 휴지통 사진을 복원하는 순간 유니크에 걸린다. 비운 뒤 복원하면 같은 사진이 두 장이 되는데, 그것은 지운 사진을
     * 알고도 다시 올린 사용자의 선택이다. 호출자가 갤러리를 잠근 뒤에 부른다(복원과 같은 잠금).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun releaseTrashed(galleryId: Long, sourceHashes: Collection<String>): Int {
        if (sourceHashes.isEmpty()) return 0
        return jdbcClient.sql(
            """
            UPDATE photos
            SET source_hash = NULL
            WHERE gallery_id = :galleryId AND deleted_at IS NOT NULL AND source_hash IN (:sourceHashes)
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .param("sourceHashes", sourceHashes.toList())
            .update()
    }
}

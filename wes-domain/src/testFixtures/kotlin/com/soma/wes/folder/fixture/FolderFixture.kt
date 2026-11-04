package com.soma.wes.folder.fixture

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

/** 폴더의 지워진 상태를 만든다. 컨셉 폴더 삭제는 trash 도메인의 SQL이 하는 일이라 엔티티에 쓰는 메서드가 없다. */
@Component
class FolderFixture(
    private val jdbcTemplate: JdbcTemplate,
) {

    fun 컨셉_삭제됨(conceptId: Long, at: ZonedDateTime) {
        jdbcTemplate.update(
            "UPDATE concept_folders SET deleted_at = ?, version = version + 1, updated_at = now() WHERE id = ?",
            at.toOffsetDateTime(), conceptId,
        )
    }
}

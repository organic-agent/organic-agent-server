package com.soma.wes.support

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 통합 테스트 사이의 데이터 정리를 소유한다. 테스트가 직접 `deleteAllInBatch()` 체인을
 * 유지하지 않는다 — 테이블이 늘 때마다 모든 테스트 파일의 삭제 순서를 손보는 비용을
 * 없애는 것이 이 클래스의 존재 이유다.
 *
 * `pg_tables`를 실행 시점에 조회하므로 새 마이그레이션이 테이블을 추가해도 여기는
 * 고칠 것이 없다. `flyway_schema_history`만 제외한다 — 지우면 다음 컨텍스트가
 * 마이그레이션을 다시 돌리려다 실패한다.
 */
@Component
class DatabaseCleaner(
    private val em: EntityManager,
) {

    @Transactional
    fun clear() {
        em.clear()

        val tables = em.createNativeQuery(
            """
            SELECT tablename FROM pg_tables
            WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
            """,
        ).resultList.joinToString(", ") { it.toString() }

        // RESTART IDENTITY는 붙이지 않는다 — id 연속성에 기대는 테스트를 만들지 않기 위해서다.
        em.createNativeQuery("TRUNCATE TABLE $tables CASCADE").executeUpdate()
    }
}

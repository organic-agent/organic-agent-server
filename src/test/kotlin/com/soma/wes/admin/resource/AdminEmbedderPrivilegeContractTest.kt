package com.soma.wes.admin.resource

import com.soma.wes.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import javax.sql.DataSource

/** 단일 V1 baseline의 column grant가 worker의 실제 SET/WHERE 표현식을 실행할 수 있는지 검증한다. */
@IntegrationTest
class AdminEmbedderPrivilegeContractTest @Autowired constructor(
    private val dataSource: DataSource,
) {

    @Test
    fun `embedder role은 worker SQL에 필요한 정확한 column 권한을 가진다`() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP ROLE IF EXISTS embedder")
                statement.execute("CREATE ROLE embedder NOLOGIN")
                try {
                    statement.execute(embedderGrantBlock())
                    statement.execute("SET ROLE embedder")
                    try {
                        statementsUsedByWorker.forEach { sql -> statement.execute(sql) }
                    } finally {
                        statement.execute("RESET ROLE")
                    }
                } finally {
                    statement.execute("DROP OWNED BY embedder")
                    statement.execute("DROP ROLE embedder")
                }
            }
        }
    }

    private fun embedderGrantBlock(): String {
        val migration = ClassPathResource("db/migration/V1__baseline.sql")
            .inputStream.bufferedReader().use { it.readText() }
        val contractStart = migration.indexOf("-- EMBEDDER_GRANT_CONTRACT_BEGIN")
        val contractEnd = migration.indexOf("-- EMBEDDER_GRANT_CONTRACT_END", contractStart)
        check(contractStart >= 0 && contractEnd >= 0) { "V1 embedder grant contract not found" }
        val contract = migration.substring(contractStart, contractEnd)
        val start = contract.indexOf("DO \$\$")
        val end = contract.indexOf("\$\$;", start)
        check(start >= 0 && end >= 0) { "V1 embedder grant block not found" }
        return contract.substring(start, end + 3)
    }

    private companion object {
        val statementsUsedByWorker = listOf(
            // fetch_targets
            """
            EXPLAIN SELECT p.id, p.storage_key
            FROM photos p
            JOIN galleries g ON g.id = p.gallery_id
            WHERE p.gallery_id = -1 AND p.status <> 'PENDING'
              AND p.deleted_at IS NULL AND g.deleted_at IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM photo_analysis a
                  WHERE a.photo_id = p.id AND a.embedding IS NOT NULL
              )
            ORDER BY p.id
            """.trimIndent(),
            // store_embeddings / complete_admin_derivative가 읽는 COALESCE·version·active 경계
            """
            EXPLAIN INSERT INTO photo_analysis (photo_id, embedding, embedding_model, created_at, updated_at)
            VALUES (-1, NULL, 'model', now(), now())
            ON CONFLICT (photo_id) DO UPDATE
            SET embedding = EXCLUDED.embedding,
                embedding_model = EXCLUDED.embedding_model,
                version = photo_analysis.version + 1,
                updated_at = now()
            """.trimIndent(),
            """
            EXPLAIN UPDATE photos
            SET preview_key = COALESCE(NULL, preview_key),
                taken_at = COALESCE(NULL, taken_at),
                camera_make = COALESCE(NULL, camera_make),
                camera_model = COALESCE(NULL, camera_model),
                exposure_time = COALESCE(NULL, exposure_time),
                f_number = COALESCE(NULL, f_number),
                iso = COALESCE(NULL, iso),
                width = COALESCE(NULL, width),
                height = COALESCE(NULL, height),
                byte_size = COALESCE(NULL, byte_size),
                status = 'EMBEDDED', version = version + 1, updated_at = now()
            WHERE id = -1 AND storage_key = 'stale-key' AND deleted_at IS NULL
              AND EXISTS (
                  SELECT 1 FROM galleries g
                  WHERE g.id = photos.gallery_id AND g.deleted_at IS NULL
              )
            """.trimIndent(),
            // exact-photo verification
            """
            EXPLAIN SELECT 1
            FROM admin_processing_jobs j
            JOIN photos p ON p.id = j.target_id
            JOIN galleries g ON g.id = p.gallery_id
            JOIN admin_photo_revisions r ON r.id = j.revision_id AND r.photo_id = p.id
            WHERE j.id = -1 AND j.attempt_count = 1 AND j.job_type = 'EMBEDDING'
              AND j.target_type = 'PHOTO' AND j.target_id = -1 AND j.revision_id = -1
              AND j.status IN ('DISPATCHING', 'DISPATCHED')
              AND p.id = -1 AND p.gallery_id = -1 AND p.storage_key = 'key'
              AND p.status <> 'PENDING' AND p.deleted_at IS NULL AND g.deleted_at IS NULL
              AND r.storage_key = 'key'
              AND j.payload ->> 'galleryId' = '-1' AND j.payload ->> 'storageKey' = 'key'
            """.trimIndent(),
            // complete_admin_embedding: 사진 CAS와 벡터 upsert를 CTE 한 문장으로
            """
            EXPLAIN WITH target AS (
                UPDATE photos p
                SET status = 'EMBEDDED', version = version + 1, updated_at = now()
                WHERE p.id = -1 AND p.gallery_id = -1 AND p.storage_key = 'key' AND p.deleted_at IS NULL
                  AND EXISTS (
                      SELECT 1 FROM admin_photo_revisions r
                      WHERE r.id = -1 AND r.photo_id = p.id AND r.storage_key = p.storage_key
                  )
                RETURNING p.id
            )
            INSERT INTO photo_analysis (photo_id, embedding, embedding_model, created_at, updated_at)
            SELECT id, NULL, 'model', now(), now() FROM target
            ON CONFLICT (photo_id) DO UPDATE
            SET embedding = EXCLUDED.embedding,
                embedding_model = EXCLUDED.embedding_model,
                version = photo_analysis.version + 1,
                updated_at = now()
            """.trimIndent(),
            // complete_admin_quality + final job CAS
            """
            EXPLAIN UPDATE photos p
            SET technical_quality_score = 80,
                technical_quality_signals = '{}'::JSONB,
                quality_analyzed_at = now(), version = version + 1, updated_at = now()
            WHERE p.id = -1 AND p.gallery_id = -1 AND p.storage_key = 'key'
              AND p.deleted_at IS NULL
              AND EXISTS (
                  SELECT 1 FROM admin_photo_revisions r
                  WHERE r.id = -1 AND r.photo_id = p.id AND r.storage_key = p.storage_key
              )
            """.trimIndent(),
            """
            EXPLAIN UPDATE admin_processing_jobs
            SET status = 'SUCCEEDED', failure_code = NULL, updated_at = now()
            WHERE id = -1 AND attempt_count = 1 AND job_type = 'QUALITY_ANALYSIS'
              AND target_type = 'PHOTO' AND target_id = -1 AND revision_id = -1
              AND status IN ('DISPATCHING', 'DISPATCHED')
              AND payload ->> 'galleryId' = '-1' AND payload ->> 'storageKey' = 'key'
            """.trimIndent(),
        )
    }
}

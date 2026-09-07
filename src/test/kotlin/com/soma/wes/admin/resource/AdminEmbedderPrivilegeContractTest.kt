package com.soma.wes.admin.resource

import com.soma.wes.support.IntegrationTest
import javax.sql.DataSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource

/** 마이그레이션의 GRANT 계약(V1 embedder·photoselect, V14 preference)이 Lambda의 실제 SQL을 실행할 수 있는지 검증한다. */
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
                    statement.execute("SET search_path TO ''")
                    statement.execute(embedderGrantBlock())
                    statement.execute("RESET search_path")
                    statement.execute("SET ROLE embedder")
                    try {
                        statementsUsedByWorker.forEach { sql -> statement.execute(sql) }
                    } finally {
                        statement.execute("RESET ROLE")
                    }
                } finally {
                    statement.execute("RESET search_path")
                    statement.execute("DROP OWNED BY embedder")
                    statement.execute("DROP ROLE embedder")
                }
            }
        }
    }

    @Test
    fun `photoselect 권한 부여는 빈 search path에서도 public 테이블에 적용된다`() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP ROLE IF EXISTS photoselect")
                statement.execute("CREATE ROLE photoselect NOLOGIN")
                try {
                    statement.execute("SET search_path TO ''")
                    statement.execute(photoselectGrantBlock())
                    statement.execute("RESET search_path")
                    statement.executeQuery(
                        """
                        SELECT
                            has_table_privilege('photoselect', 'public.galleries', 'SELECT'),
                            has_table_privilege('photoselect', 'public.photo_analysis', 'INSERT'),
                            has_table_privilege('photoselect', 'public.ai_analysis_jobs', 'UPDATE')
                        """.trimIndent(),
                    ).use { result ->
                        check(result.next())
                        check(result.getBoolean(1) && result.getBoolean(2) && result.getBoolean(3))
                    }
                } finally {
                    statement.execute("RESET search_path")
                    statement.execute("DROP OWNED BY photoselect")
                    statement.execute("DROP ROLE photoselect")
                }
            }
        }
    }

    @Test
    fun `photoselect role은 preference 학습기가 쓰는 SQL을 실행할 수 있다`() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP ROLE IF EXISTS photoselect")
                statement.execute("CREATE ROLE photoselect NOLOGIN")
                try {
                    statement.execute("SET search_path TO ''")
                    statement.execute(photoselectGrantBlock())
                    statement.execute(preferenceGrantBlock())
                    statement.execute("RESET search_path")
                    statement.execute("SET ROLE photoselect")
                    try {
                        statementsUsedByPreferenceTrainer.forEach { sql -> statement.execute(sql) }
                        statement.executeQuery("SELECT id, active FROM preference_models ORDER BY id").use { result ->
                            check(result.next() && !result.getBoolean("active")) { "첫 행의 active가 내려가야 한다" }
                            check(result.next() && result.getBoolean("active")) { "두 번째 행이 active여야 한다" }
                            check(!result.next())
                        }
                    } finally {
                        statement.execute("RESET ROLE")
                    }
                } finally {
                    statement.execute("RESET search_path")
                    statement.execute("DROP OWNED BY photoselect")
                    statement.execute("DROP ROLE photoselect")
                }
            }
        }
    }

    private fun embedderGrantBlock(): String = grantBlock("V1__baseline.sql", "EMBEDDER_GRANT_CONTRACT")

    private fun photoselectGrantBlock(): String = grantBlock("V1__baseline.sql", "PHOTOSELECT_GRANT_CONTRACT")

    private fun preferenceGrantBlock(): String = grantBlock("V14__preference_models.sql", "PREFERENCE_GRANT_CONTRACT")

    /** 마이그레이션 파일의 `-- {marker}_BEGIN` … `_END` 사이에 있는 DO 블록 하나를 꺼낸다. */
    private fun grantBlock(migrationFile: String, marker: String): String {
        val migration = ClassPathResource("db/migration/$migrationFile")
            .inputStream.bufferedReader().use { it.readText() }
        val contractStart = migration.indexOf("-- ${marker}_BEGIN")
        val contractEnd = migration.indexOf("-- ${marker}_END", contractStart)
        check(contractStart >= 0 && contractEnd >= 0) { "$migrationFile $marker not found" }
        val contract = migration.substring(contractStart, contractEnd)
        val start = contract.indexOf("DO \$\$")
        val end = contract.indexOf("\$\$;", start)
        check(start >= 0 && end >= 0) { "$migrationFile $marker DO block not found" }
        return contract.substring(start, end + 3)
    }

    private companion object {
        // AI repo preference/store.py — DbStore.list_closed_galleries · read_gallery · write_model
        val statementsUsedByPreferenceTrainer = listOf(
            """
            EXPLAIN SELECT g.id FROM galleries g
            WHERE g.status = 'CLOSED'
              AND EXISTS (SELECT 1 FROM photo_selections s JOIN photo_selection_items i ON i.selection_id = s.id
                          WHERE s.gallery_id = g.id AND s.deleted_at IS NULL)
              AND EXISTS (SELECT 1 FROM photos p JOIN photo_analysis a ON a.photo_id = p.id
                          WHERE p.gallery_id = g.id AND p.deleted_at IS NULL AND a.model_version IS NOT NULL)
            ORDER BY g.updated_at, g.id
            """.trimIndent(),
            "EXPLAIN SELECT shoot_type FROM galleries WHERE id = -1",
            """
            EXPLAIN SELECT p.id, p.original_file_name, p.display_order,
                   a.technical_pct, a.aesthetic_pct, a.sub_scores, a.subjects,
                   a.cluster_id, a.cluster_rank, a.embed_group_id,
                   a.embedding, a.clip_embedding, a.embedding_model, a.model_version
            FROM photos p
            JOIN photo_analysis a ON a.photo_id = p.id
            WHERE p.gallery_id = -1 AND p.deleted_at IS NULL
              AND a.model_version IS NOT NULL AND a.embedding IS NOT NULL AND a.clip_embedding IS NOT NULL
            ORDER BY p.display_order, p.id
            """.trimIndent(),
            """
            EXPLAIN SELECT i.photo_id
            FROM photo_selection_items i
            JOIN photo_selections s ON s.id = i.selection_id
            WHERE s.gallery_id = -1 AND s.deleted_at IS NULL
            """.trimIndent(),
            // write_model: 첫 학습은 active 행이 없으므로 INSERT만, 다음 학습은 이전 active를 내리고 INSERT
            """
            INSERT INTO preference_models
                (embedding_model, model_version, feature_spec, w_scalar, w_emb, bias, lambda,
                 n_galleries, n_positives, train_gallery_ids, holdout, active)
            VALUES ('dinov3', 'score-v3', 'pref-v1', ARRAY[0.1, 0.2], array_fill(0.0, ARRAY[1536])::vector, 0.0, 0.5,
                    1, 30, ARRAY[8]::bigint[], '{"rows": []}'::jsonb, true)
            """.trimIndent(),
            "UPDATE preference_models SET active = false WHERE active",
            """
            INSERT INTO preference_models
                (embedding_model, model_version, feature_spec, w_scalar, w_emb, bias, lambda,
                 n_galleries, n_positives, train_gallery_ids, holdout, active)
            VALUES ('dinov3', 'score-v3', 'pref-v1', ARRAY[0.1, 0.2], array_fill(0.0, ARRAY[1536])::vector, 0.0, 0.5,
                    2, 60, ARRAY[8, 9]::bigint[], '{"rows": []}'::jsonb, true)
            """.trimIndent(),
        )
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

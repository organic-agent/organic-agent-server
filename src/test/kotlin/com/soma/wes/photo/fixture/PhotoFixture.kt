package com.soma.wes.photo.fixture

import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoAnalysis
import com.soma.wes.photo.repository.PhotoAnalysisRepository
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.support.TestSequence
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class PhotoFixture(
    private val photoRepository: PhotoRepository,
    private val photoAnalysisRepository: PhotoAnalysisRepository,
    private val jdbcTemplate: JdbcTemplate,
) {

    fun 업로드된_사진(galleryId: Long, count: Int): List<Long> = 사진(galleryId, count, uploaded = true)

    /** 업로드 URL만 발급된 PENDING 상태. 완료 통보가 오지 않은 사진이다. */
    fun 대기중_사진(galleryId: Long, count: Int): List<Long> = 사진(galleryId, count, uploaded = false)

    /**
     * 벡터까지 적재된 사진. 벡터 값은 의미가 없다(전부 같은 방향) — 클러스터 결과를
     * 보는 테스트는 [벡터_적재]로 원하는 벡터를 직접 넣는다.
     */
    fun 임베딩된_사진(galleryId: Long, count: Int): List<Long> =
        업로드된_사진(galleryId, count).onEach { photoId ->
            벡터_적재(photoId, FloatArray(PhotoAnalysis.EMBEDDING_DIMENSION).also { it[0] = 1f })
        }

    /** 사진에 벡터를 싣는다. 임베더 Lambda가 분석 행을 UPSERT 하는 것을 흉내 낸다 — 사진 상태는 건드리지 않는다. */
    fun 벡터_적재(photoId: Long, vector: FloatArray): PhotoAnalysis =
        photoAnalysisRepository.saveAndFlush(
            PhotoAnalysis.embeddedBy(photoId = photoId, vector = vector, model = EMBEDDING_MODEL),
        )

    /** score(GPU 워커·Lambda 폴백)가 CLIP 벡터·점수를 적재한 것을 흉내 낸다 — 벡터가 이미 있는 사진에만. */
    fun 점수_적재(photoId: Long) {
        jdbcTemplate.update(
            """
            UPDATE photo_analysis
            SET clip_embedding = embedding, subjects = 'couple', pipeline_version = 'score-test', analyzed_at = now(), updated_at = now()
            WHERE photo_id = ? AND embedding IS NOT NULL
            """.trimIndent(),
            photoId,
        )
    }

    /** categorize 가 백분위·연사·임베딩 그룹을 적재한 것을 흉내 낸다 — 점수가 이미 있는 사진에만. */
    fun 백분위_적재(photoId: Long, embedGroupId: Int = 1) {
        jdbcTemplate.update(
            """
            UPDATE photo_analysis
            SET technical_pct = 80.0, aesthetic_pct = 70.0, burst_id = 1, burst_rank = 0, embed_group_id = ?, updated_at = now()
            WHERE photo_id = ? AND clip_embedding IS NOT NULL
            """.trimIndent(),
            embedGroupId,
            photoId,
        )
    }

    /**
     * 미리보기까지 만들어진 사진. 임베더가 `previews/`를 올리고 키를 적은 상태라 AI 호출 재료가 있다.
     *
     * 미리보기 키를 쓰는 테스트(보정 요청 정제, 비교샷)는 이 사진을 쓴다 — [업로드된_사진]은 아직 키가 없다.
     */
    fun 미리보기_있는_사진(galleryId: Long): Photo {
        val photoId = 업로드된_사진(galleryId, 1).first()
        val photo = photoRepository.findById(photoId).orElseThrow()
        photo.previewKey = "previews/${photo.storageKey}"
        return photoRepository.saveAndFlush(photo)
    }

    /** 임베더·score 가 사진 단위 결정적 실패를 남긴 것을 흉내 낸다. 이 사진은 기대 장수에서 빠진다. */
    fun 분석_실패(photoId: Long, error: String = "DECODE_FAILED") {
        jdbcTemplate.update(
            """
            INSERT INTO photo_analysis (photo_id, error, created_at, updated_at) VALUES (?, ?, now(), now())
            ON CONFLICT (photo_id) DO UPDATE SET error = EXCLUDED.error, updated_at = now()
            """.trimIndent(),
            photoId,
            error,
        )
    }

    private fun 사진(galleryId: Long, count: Int, uploaded: Boolean): List<Long> =
        (1..count).map { index ->
            val photo = Photo(
                galleryId = galleryId,
                storageKey = "galleries/$galleryId/${TestSequence.next()}.jpg",
                originalFileName = "$index.jpg",
                contentType = "image/jpeg",
                displayOrder = index,
            )
            if (uploaded) {
                photo.markUploaded(java.time.ZonedDateTime.now())
            }
            photoRepository.save(photo).requiredId
        }

    /** 사진 한도 경계의 대량 배경 데이터. 분석 행 없이 실제 업로드된 사진만 만든다. */
    fun 대량_업로드된_사진(galleryId: Long, count: Int) {
        val sequence = TestSequence.next()
        jdbcTemplate.update(
            """
            INSERT INTO photos (gallery_id, storage_key, original_file_name, content_type, display_order, status, version, created_at, updated_at)
            SELECT ?, 'galleries/' || ? || '/quota-' || ? || '-' || n || '.jpg', n || '.jpg', 'image/jpeg', n, 'UPLOADED', 0, now(), now()
            FROM generate_series(1, ?) AS n
            """.trimIndent(),
            galleryId, galleryId, sequence, count,
        )
    }

    companion object {
        const val EMBEDDING_MODEL = "facebook/dinov3-vitb16-pretrain-lvd1689m"
    }
}

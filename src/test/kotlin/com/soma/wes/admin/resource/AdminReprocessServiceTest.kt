package com.soma.wes.admin.resource

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminReprocessRequest
import com.soma.wes.admin.resource.dto.AdminResourceResponse
import com.soma.wes.admin.resource.dto.CreateAdminResourceRequest
import com.soma.wes.admin.resource.service.AdminReprocessService
import com.soma.wes.admin.resource.service.AdminResourceService
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.support.FakeAiTaskSender
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

@IntegrationTest
class AdminReprocessServiceTest @Autowired constructor(
    private val service: AdminReprocessService,
    private val resourceService: AdminResourceService,
    private val adminAccountFixture: AdminAccountFixture,
    private val analysisJobRepository: AnalysisJobRepository,
    private val aiTaskSender: FakeAiTaskSender,
    private val jdbcTemplate: JdbcTemplate,
) {

    @BeforeEach
    fun setUp() {
        aiTaskSender.reset()
    }

    @Test
    fun `재처리는 분석 행을 지우고 배정 추적을 초기화한 뒤 잡을 만든다`() {
        // given — 벡터가 있고 배정 상한에 닿은 사진
        val actor = adminAccountFixture.관리자("reprocess-owner")
        val gallery = createGallery(actor.requiredId)
        val photos = embeddedPhotos(gallery.id, count = 2)
        jdbcTemplate.update("UPDATE photos SET dispatched_at = now(), embed_attempts = 3 WHERE id IN (?, ?)", photos[0], photos[1])
        val request = AdminReprocessRequest(reason = "모델 교체 재계산", expectedVersion = gallery.version, idempotencyKey = "gallery-reprocess-001")

        // when
        val response = service.reprocess(actor.requiredId, AdminResourceType.GALLERY, gallery.id, request, "127.0.0.1")

        // then
        val job = analysisJobRepository.findFirstByGalleryIdOrderByIdDesc(gallery.id)!!
        val attempts = jdbcTemplate.queryForList("SELECT dispatched_at, embed_attempts FROM photos WHERE gallery_id = ?", gallery.id)
        assertSoftly { softly ->
            softly.assertThat(response.accepted).isTrue()
            softly.assertThat(response.targets).isEqualTo(2L)
            softly.assertThat(analysisRows(gallery.id)).isZero()
            softly.assertThat(attempts).allSatisfy { row ->
                assertThat(row["dispatched_at"]).isNull()
                assertThat(row["embed_attempts"]).isEqualTo(0)
            }
            softly.assertThat(job.status).isEqualTo(AnalysisStatus.ANALYZING)
            // Lambda 를 직접 부르지 않는다 — 다시 배정하는 것은 스윕이다.
            softly.assertThat(aiTaskSender.tasks).isEmpty()
        }
    }

    @Test
    fun `같은 멱등성 키의 갤러리 재처리는 한 번만 받아들이고 살아 있는 잡이 있으면 새 잡을 만들지 않는다`() {
        val actor = adminAccountFixture.관리자("reprocess-idempotent")
        val gallery = createGallery(actor.requiredId)
        val request = AdminReprocessRequest("임베딩 결과 복구", gallery.version, "gallery-reprocess-002")

        val first = service.reprocess(actor.requiredId, AdminResourceType.GALLERY, gallery.id, request, "127.0.0.1")
        val duplicate = service.reprocess(actor.requiredId, AdminResourceType.GALLERY, gallery.id, request, "127.0.0.1")
        val again = service.reprocess(
            actor.requiredId,
            AdminResourceType.GALLERY,
            gallery.id,
            request.copy(idempotencyKey = "gallery-reprocess-003"),
            "127.0.0.1",
        )

        assertSoftly { softly ->
            softly.assertThat(first.accepted).isTrue()
            softly.assertThat(duplicate.accepted).isFalse()
            softly.assertThat(duplicate.targets).isEqualTo(first.targets)
            softly.assertThat(again.accepted).isTrue()
            softly.assertThat(analysisJobRepository.findAll().filter { it.galleryId == gallery.id }).hasSize(1)
        }
    }

    @Test
    fun `같은 키로 사유를 바꾸면 다른 요청으로 판단해 차단한다`() {
        val actor = adminAccountFixture.관리자("reprocess-conflict")
        val gallery = createGallery(actor.requiredId)
        val first = AdminReprocessRequest("첫 재처리", gallery.version, "gallery-reprocess-004")
        service.reprocess(actor.requiredId, AdminResourceType.GALLERY, gallery.id, first, "127.0.0.1")

        assertThatThrownBy {
            service.reprocess(
                actor.requiredId,
                AdminResourceType.GALLERY,
                gallery.id,
                first.copy(reason = "다른 사유"),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.IDEMPOTENCY_KEY_REUSED)
        }
    }

    @Test
    fun `실행기가 설정되지 않았으면 리셋하지 않는다`() {
        val actor = adminAccountFixture.관리자("reprocess-unconfigured")
        val gallery = createGallery(actor.requiredId)
        embeddedPhotos(gallery.id, count = 1)
        aiTaskSender.available = false

        assertThatThrownBy {
            service.reprocess(
                actor.requiredId,
                AdminResourceType.GALLERY,
                gallery.id,
                AdminReprocessRequest("실행기 없음", gallery.version, "gallery-reprocess-005"),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AnalysisException::class.java) {
            assertThat(it.errorCode).isEqualTo(AnalysisErrorCode.AI_TASK_NOT_CONFIGURED)
        }
        assertThat(analysisRows(gallery.id)).isEqualTo(1)
        assertThat(analysisJobRepository.count()).isZero()
    }

    /** 관리자 컨텍스트에는 사진 픽스처가 없다(admin 패키지만 스캔) — 업로드가 끝나고 벡터까지 있는 사진을 SQL 로 만든다. */
    private fun embeddedPhotos(galleryId: Long, count: Int): List<Long> = (1..count).map { index ->
        val photoId = jdbcTemplate.queryForObject(
            """
            INSERT INTO photos (gallery_id, storage_key, original_file_name, display_order, status, content_type, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'UPLOADED', 'image/jpeg', now(), now())
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            galleryId, "galleries/$galleryId/reprocess-$index-${System.nanoTime()}.jpg", "$index.jpg", index,
        )!!
        jdbcTemplate.update(
            "INSERT INTO photo_analysis (photo_id, embedding, embedding_model, created_at, updated_at) VALUES (?, array_fill(0.1, ARRAY[${com.soma.wes.photo.domain.PhotoAnalysis.EMBEDDING_DIMENSION}])::vector, 'test', now(), now())",
            photoId,
        )
        photoId
    }

    private fun analysisRows(galleryId: Long): Long = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM photo_analysis a JOIN photos p ON p.id = a.photo_id WHERE p.gallery_id = ?",
        Long::class.java,
        galleryId,
    )!!

    private fun createGallery(actorAdminId: Long): AdminResourceResponse {
        val user = resourceService.create(
            actorAdminId,
            AdminResourceType.USER,
            CreateAdminResourceRequest(
                "재처리 테스트 사용자",
                mapOf("provider" to "GOOGLE", "providerId" to "reprocess-$actorAdminId", "nickname" to "재처리 사용자"),
            ),
            "127.0.0.1",
        )
        val studio = resourceService.create(
            actorAdminId,
            AdminResourceType.STUDIO,
            CreateAdminResourceRequest(
                "재처리 테스트 스튜디오",
                mapOf("ownerUserId" to user.id, "name" to "재처리", "galleryUrl" to "reprocess-$actorAdminId"),
            ),
            "127.0.0.1",
        )
        return resourceService.create(
            actorAdminId,
            AdminResourceType.GALLERY,
            CreateAdminResourceRequest(
                "재처리 테스트 갤러리",
                mapOf("workspaceId" to studio.id, "title" to "재처리 갤러리"),
            ),
            "127.0.0.1",
        )
    }
}

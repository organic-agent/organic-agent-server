package com.soma.wes.admin.resource

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminReprocessRequest
import com.soma.wes.admin.resource.dto.CreateAdminResourceRequest
import com.soma.wes.admin.resource.service.AdminReprocessService
import com.soma.wes.admin.resource.service.AdminResourceService
import com.soma.wes.embedding.service.EmbeddingInvoker
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean

@IntegrationTest
class AdminReprocessServiceTest @Autowired constructor(
    private val service: AdminReprocessService,
    private val resourceService: AdminResourceService,
    private val adminAccountFixture: AdminAccountFixture,
) {

    @MockitoBean
    private lateinit var embeddingInvoker: EmbeddingInvoker

    @BeforeEach
    fun setUp() {
        whenever(embeddingInvoker.isAvailable).thenReturn(true)
    }

    @Test
    fun `같은 멱등성 키의 갤러리 재처리는 외부 실행기를 한 번만 호출한다`() {
        val actor = adminAccountFixture.관리자("reprocess-owner")
        val gallery = createGallery(actor.requiredId)
        val request = AdminReprocessRequest(
            reason = "임베딩 결과 복구",
            expectedVersion = gallery.version,
            idempotencyKey = "gallery-reprocess-001",
            force = true,
        )

        val first = service.reprocess(actor.requiredId, AdminResourceType.GALLERY, gallery.id, request, "127.0.0.1")
        val duplicate = service.reprocess(actor.requiredId, AdminResourceType.GALLERY, gallery.id, request, "127.0.0.1")

        assertThat(first.accepted).isTrue()
        assertThat(duplicate.accepted).isFalse()
        assertThat(duplicate.targets).isEqualTo(first.targets)
        verify(embeddingInvoker).invoke(gallery.id, true)
    }

    @Test
    fun `같은 키로 force 값을 바꾸면 다른 요청으로 판단해 차단한다`() {
        val actor = adminAccountFixture.관리자("reprocess-conflict")
        val gallery = createGallery(actor.requiredId)
        val first = AdminReprocessRequest("첫 재처리", gallery.version, "gallery-reprocess-002", force = false)
        service.reprocess(actor.requiredId, AdminResourceType.GALLERY, gallery.id, first, "127.0.0.1")

        assertThatThrownBy {
            service.reprocess(
                actor.requiredId,
                AdminResourceType.GALLERY,
                gallery.id,
                first.copy(force = true),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.IDEMPOTENCY_KEY_REUSED)
        }
    }

    private fun createGallery(actorAdminId: Long): com.soma.wes.admin.resource.dto.AdminResourceResponse {
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
                mapOf("userId" to user.id, "name" to "재처리", "galleryUrl" to "reprocess-$actorAdminId"),
            ),
            "127.0.0.1",
        )
        return resourceService.create(
            actorAdminId,
            AdminResourceType.GALLERY,
            CreateAdminResourceRequest(
                "재처리 테스트 갤러리",
                mapOf("studioId" to studio.id, "title" to "재처리 갤러리"),
            ),
            "127.0.0.1",
        )
    }
}

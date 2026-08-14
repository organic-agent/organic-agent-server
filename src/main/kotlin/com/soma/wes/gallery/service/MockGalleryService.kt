package com.soma.wes.gallery.service

import com.soma.wes.gallery.config.MockGalleryProperties
import com.soma.wes.gallery.dto.request.CreateGalleryRequest
import com.soma.wes.gallery.dto.response.GalleryResponse
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.support.MockGalleryCopyPlan
import com.soma.wes.gallery.support.MockGallerySeeder
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.service.PhotoStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Mock 갤러리 — 온보딩 직후의 작가가 실제 촬영 없이 제품을 눌러볼 수 있는 샘플 갤러리.
 *
 * 결과물은 완전히 일반적인 갤러리다. 템플릿 갤러리(운영자 스튜디오가 일반 업로드·임베딩
 * 파이프라인으로 한 번 시드해 둔 진짜 갤러리)의 사진 행과 S3 객체를 새 갤러리의 자기 키
 * 공간(`galleries/{newId}/…`)으로 복제하므로, 만들어진 뒤에는 삭제·임베딩·클러스터링·리셋
 * 어디에도 특수 취급이 없다. 같은 이유로 스튜디오당 개수 제한도 없다 — 부를 때마다 새로
 * 만들고, 버튼을 언제 감출지는 화면이 정한다.
 *
 * 클래스에 `@Transactional`이 없는 것은 의도다. S3 복사가 흐름 한가운데 있어 전체를 한
 * 트랜잭션으로 감싸면 객체 수백 개를 복사하는 내내 커넥션을 붙잡는다. DB 단계는
 * [MockGallerySeeder]가 각자의 트랜잭션으로 수행한다.
 */
@Service
class MockGalleryService(
    private val properties: MockGalleryProperties,
    private val seeder: MockGallerySeeder,
    private val photoStorage: PhotoStorage,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun create(userId: Long, request: CreateGalleryRequest?): GalleryResponse {
        if (!properties.isConfigured) {
            throw GalleryException(GalleryErrorCode.MOCK_GALLERY_NOT_READY)
        }
        val templates = seeder.loadTemplatePhotos(properties.templateGalleryId)
        val gallery = seeder.createGallery(userId, request)
        val galleryId = gallery.requiredId

        val plans = buildPlans(galleryId, templates)
        val copiedKeys = mutableListOf<String>()
        try {
            copyObjects(plans, copiedKeys)
            seeder.persistPhotos(gallery, plans)
        } catch (e: Exception) {
            compensate(galleryId, copiedKeys)
            throw e
        }

        log.info("Mock 갤러리 생성: galleryId={}, templateGalleryId={}, photos={}", galleryId, properties.templateGalleryId, plans.size)
        return GalleryResponse.from(gallery)
    }

    private fun buildPlans(galleryId: Long, templates: List<Photo>): List<MockGalleryCopyPlan> =
        templates.map { source ->
            val storageKey = photoStorage.buildKey(galleryId, source.originalFileName)
            MockGalleryCopyPlan(
                source = source,
                storageKey = storageKey,
                // 파생본 위치는 원본 키에서 파생되는 고정 규칙이다. 휴지통 물리 삭제의
                // TrashEraser.expectedPreviewKeyOf, 임베딩 Lambda의 preview_key_for와
                // 같아야 물리 삭제가 이 복사본의 미리보기를 찾아 지운다.
                previewKey = source.previewKey?.let {
                    "previews/${storageKey.substringBeforeLast('.', storageKey)}.jpg"
                },
            )
        }

    private fun copyObjects(plans: List<MockGalleryCopyPlan>, copiedKeys: MutableList<String>) {
        plans.forEach { plan ->
            photoStorage.copy(plan.source.storageKey, plan.storageKey)
            copiedKeys += plan.storageKey
            if (plan.previewKey != null) {
                photoStorage.copy(checkNotNull(plan.source.previewKey), plan.previewKey)
                copiedKeys += plan.previewKey
            }
        }
    }

    /**
     * 실패한 생성의 흔적 — 복사해 둔 객체와 사진 없는 갤러리 행 — 을 걷어낸다.
     *
     * best-effort다. 보상까지 실패해도 남는 것은 아무 행도 가리키지 않는 S3 객체와 빈
     * 갤러리뿐이라 데이터가 틀려지지는 않는다. 원래 예외를 삼키지 않도록 로그만 남긴다.
     */
    private fun compensate(galleryId: Long, copiedKeys: List<String>) {
        runCatching { photoStorage.deleteAll(copiedKeys) }
            .onFailure { log.warn("Mock 갤러리 보상 삭제 실패: galleryId={}, keys={}", galleryId, copiedKeys.size, it) }
        runCatching { seeder.discard(galleryId) }
            .onFailure { log.warn("Mock 갤러리 행 보상 삭제 실패: galleryId={}", galleryId, it) }
    }
}

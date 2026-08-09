package com.soma.wes.gallery.service

import com.soma.wes.gallery.config.MockGalleryProperties
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryType
import com.soma.wes.gallery.dto.request.CreateGalleryRequest
import com.soma.wes.gallery.dto.response.GalleryResponse
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.support.MockGalleryTemplateLoader
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.repository.StudioRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/** 버전이 고정된 샘플 사진과 사전 계산 임베딩을 한 트랜잭션으로 seed한다. */
@Service
class MockGalleryService(
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val photoRepository: PhotoRepository,
    private val templateLoader: MockGalleryTemplateLoader,
    private val properties: MockGalleryProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 같은 스튜디오가 여러 번 요청해도 최초 갤러리를 그대로 반환한다.
     *
     * 아직 존재하지 않는 행은 잠글 수 없어 스튜디오 부모 행을 먼저 잠근다. 같은 스튜디오의
     * 경쟁 요청은 잠금 뒤 기존 Mock 갤러리를 다시 읽으므로 사진을 중복 seed하지 않는다.
     * 다른 스튜디오는 서로 다른 부모 행을 잠가 병렬로 생성할 수 있다.
     *
     * 기능 gate는 기존 행 조회 뒤에 본다. 운영에서 기능을 다시 꺼도 이미 만들어진 사용자의
     * 재시도까지 503으로 바뀌지 않고 같은 id를 되돌려 주기 위해서다.
     */
    @Transactional
    fun create(userId: Long, request: CreateGalleryRequest?): GalleryResponse {
        val studio = studioRepository.findByUserIdForUpdate(userId)
            ?: throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        val studioId = checkNotNull(studio.id) { "저장되지 않은 스튜디오입니다." }

        galleryRepository.findByStudioIdAndGalleryType(studioId, GalleryType.MOCK)?.let { existing ->
            log.info("기존 Mock 갤러리 반환: studioId={}, galleryId={}", studioId, existing.id)
            return GalleryResponse.from(existing)
        }

        if (!properties.enabled) {
            throw GalleryException(GalleryErrorCode.MOCK_GALLERY_NOT_READY)
        }

        val template = templateLoader.load()
        val gallery = galleryRepository.save(
            Gallery.createMock(
                studioId = studioId,
                templateVersion = template.templateVersion,
                title = request?.title ?: DEFAULT_TITLE,
                selectionDeadline = request?.selectionDeadline,
                targetPhotoCount = request?.targetPhotoCount,
                at = ZonedDateTime.now(clock),
            ),
        )
        val galleryId = checkNotNull(gallery.id) { "저장되지 않은 갤러리입니다." }

        val photos = template.photos.map { sample ->
            Photo.createSharedTemplate(
                galleryId = galleryId,
                storageKey = sample.storageKey,
                previewKey = sample.previewKey,
                originalFileName = sample.originalFileName,
                contentType = sample.contentType,
                displayOrder = sample.displayOrder,
                embedding = sample.embedding.toFloatArray(),
            )
        }
        photoRepository.saveAll(photos)

        log.info(
            "Mock 갤러리 생성: studioId={}, galleryId={}, templateVersion={}, photos={}",
            studioId,
            galleryId,
            template.templateVersion,
            photos.size,
        )
        return GalleryResponse.from(gallery)
    }

    companion object {
        const val DEFAULT_TITLE = "샘플 갤러리"
    }
}

package com.soma.wes.gallery.support

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.dto.request.CreateGalleryRequest
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.studio.support.StudioWriteAdmission
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/**
 * [com.soma.wes.gallery.service.MockGalleryService]의 DB 단계들.
 *
 * 서비스가 아니라 별도 빈인 이유는 트랜잭션 경계다. Mock 갤러리 생성은 S3 복사를 사이에 두고
 * DB 쓰기가 앞뒤로 갈라지는데, 오케스트레이터가 자기 `@Transactional` 메서드를 부르면 프록시를
 * 우회해 애노테이션이 조용히 무시된다(OAuthLoginProcessor와 같은 이유). 각 메서드가 자기
 * 트랜잭션을 가지므로, S3 복사 동안 커넥션을 붙잡지 않는다.
 */
@Service
class MockGallerySeeder(
    private val studioWriteAdmission: StudioWriteAdmission,
    private val galleryRepository: GalleryRepository,
    private val photoRepository: PhotoRepository,
    private val clock: Clock,
) {

    /**
     * 복제 원본이 될 사진들. 임베딩까지 끝난 사진만 고른다 — PENDING은 S3 객체가 없을 수
     * 있고, UPLOADED는 벡터가 없어 복제해도 "즉시 체험"이 되지 않는다.
     */
    @Transactional(readOnly = true)
    fun loadTemplatePhotos(templateGalleryId: Long): List<Photo> {
        if (!galleryRepository.existsById(templateGalleryId)) {
            throw GalleryException(GalleryErrorCode.MOCK_GALLERY_NOT_READY)
        }
        val templates = photoRepository.findAllByGalleryIdOrderByDisplayOrderAsc(templateGalleryId)
            .filter { it.status == PhotoStatus.EMBEDDED && it.embedding != null }
        if (templates.isEmpty()) {
            throw GalleryException(GalleryErrorCode.MOCK_GALLERY_NOT_READY)
        }
        return templates
    }

    /**
     * 갤러리 행을 S3 복사보다 먼저 만든다. 복사 목적지 키(`galleries/{id}/…`)가 갤러리 id를
     * 요구하는데 id는 IDENTITY라 저장 전에는 없다. 복사나 사진 저장이 실패하면 이 행은
     * [discard]로 걷어낸다.
     */
    @Transactional
    fun createGallery(userId: Long, request: CreateGalleryRequest?): Gallery {
        val studio = studioWriteAdmission.requireWritableByUserId(userId)

        return galleryRepository.save(
            Gallery.create(
                studioId = checkNotNull(studio.id) { "저장되지 않은 스튜디오입니다." },
                title = request?.title ?: DEFAULT_TITLE,
                selectionDeadline = request?.selectionDeadline,
                maxSelectablePhotoCount = request?.maxSelectablePhotoCount,
                at = ZonedDateTime.now(clock),
            ),
        )
    }

    /**
     * 복사된 객체들을 가리키는 사진 행을 만든다. [createGallery]와 트랜잭션이 다르므로
     * 그 사이에 hard delete가 시작됐을 수 있다 — 여기서 admission을 다시 지나, 삭제 스냅샷에
     * 잡히지 않은 사진 행이 뒤늦게 생기는 일을 막는다.
     *
     * `displayOrder`는 템플릿 순서 그대로 0부터 다시 매긴다. 템플릿 갤러리의 번호를 복사하면
     * 운영자가 템플릿에서 사진을 지웠을 때 구멍 난 순서가 그대로 전파된다.
     */
    @Transactional
    fun persistPhotos(gallery: Gallery, plans: List<MockGalleryCopyPlan>) {
        studioWriteAdmission.requireWritable(gallery.studioId)

        photoRepository.saveAll(
            plans.mapIndexed { index, plan ->
                Photo.copyOf(
                    source = plan.source,
                    galleryId = checkNotNull(gallery.id) { "저장되지 않은 갤러리입니다." },
                    storageKey = plan.storageKey,
                    previewKey = plan.previewKey,
                    displayOrder = index,
                )
            },
        )
    }

    /** 실패 보상 — 사진 없이 남은 갤러리 행을 걷어낸다. 이미 없으면 조용히 지나간다. */
    @Transactional
    fun discard(galleryId: Long) {
        galleryRepository.deleteById(galleryId)
    }

    companion object {
        const val DEFAULT_TITLE = "샘플 갤러리"
    }
}

package com.soma.wes.embedding.service

import com.soma.wes.embedding.dto.response.EmbeddingRunResponse
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.repository.PhotoRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 갤러리 하나의 임베딩 계산을 시작시킨다.
 *
 * 벡터를 만드는 일 자체는 이 서버가 하지 않는다 — 이미지 바이트가 여기를 거치지 않는 것과
 * 같은 이유다. 모델 가중치만 수백 MB이고 추론은 CPU를 통째로 먹는다.
 */
@Service
class EmbeddingService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoRepository: PhotoRepository,
    private val embeddingInvoker: EmbeddingInvoker,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun run(galleryId: Long, userId: Long, force: Boolean): EmbeddingRunResponse {
        galleryAccessPolicy.requirePhotographer(galleryId, userId)

        // 기동이 아니라 여기서 실패한다. 로컬·테스트에는 실행기가 없는 것이 정상이라
        // 설정이 비어 있다고 앱을 못 뜨게 만들면 개발이 막힌다.
        if (!embeddingInvoker.isAvailable) {
            throw PhotoException(PhotoErrorCode.EMBEDDING_NOT_CONFIGURED)
        }

        // 실제 실행기와 똑같이 PENDING을 제외한다. URL만 발급된 사진은 S3 객체가 아직 없을 수 있어
        // 실행기가 읽지 않으므로, 여기서 포함하면 응답의 대상 수와 실제 처리 수가 어긋난다.
        val targets = if (force) {
            photoRepository.countByGalleryIdAndStatusNot(galleryId, PhotoStatus.PENDING)
        } else {
            photoRepository.countByGalleryIdAndStatusNotAndEmbeddingIsNull(galleryId, PhotoStatus.PENDING)
        }

        embeddingInvoker.invoke(galleryId, force)

        log.info("임베딩 실행 요청: galleryId={}, force={}, 대상={}장", galleryId, force, targets)

        return EmbeddingRunResponse(galleryId = galleryId, targets = targets)
    }
}

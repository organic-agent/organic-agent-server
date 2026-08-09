package com.soma.wes.embedding.service

import com.soma.wes.embedding.dto.response.EmbeddingRunResponse
import com.soma.wes.gallery.domain.GalleryType
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.domain.PhotoStorageOwnership
import com.soma.wes.photo.repository.PhotoRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 갤러리 하나의 임베딩 계산을 시작시킨다.
 *
 * 벡터를 만드는 일 자체는 이 서버가 하지 않는다 — 이미지 바이트가 여기를 거치지 않는 것과
 * 같은 이유다. 모델 가중치만 수백 MB이고 추론은 CPU를 통째로 먹는다.
 *
 * 무엇을 어떻게 부르는지는 [EmbeddingInvoker] 구현이 알고, 이 서비스는 권한과 집계만 본다.
 * 호출이 비동기라는 사실은 응답에도 드러난다 — 돌려주는 것은 결과가 아니라 대상 장수뿐이고,
 * 진행 상황은 `GET /photos/summary`의 embedded 수로 확인한다.
 */
// 클래스 단위 @Transactional을 걸지 않는다. 걸면 외부 호출이 트랜잭션 안에서 일어나
// 그동안 커넥션 하나를 붙잡고 있게 된다. 여기서 DB를 쓰는 것은 count 하나뿐이라
// 리포지토리 호출 자체의 트랜잭션으로 충분하다.
@Service
class EmbeddingService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoRepository: PhotoRepository,
    private val embeddingInvoker: EmbeddingInvoker,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun run(galleryId: Long, userId: Long, force: Boolean): EmbeddingRunResponse {
        val gallery = galleryAccessPolicy.requirePhotographer(galleryId, userId)

        // Lambda의 실제 SELECT와 같은 조건이다. 공유 템플릿은 WES-22에서 만들어 둔 불변
        // 임베딩을 여러 갤러리가 참조하므로 force에도 다시 계산하지 않는다.
        val targets = if (force) {
            photoRepository.countByGalleryIdAndStorageOwnershipAndStatusNot(
                galleryId,
                PhotoStorageOwnership.GALLERY,
                PhotoStatus.PENDING,
            )
        } else {
            photoRepository.countByGalleryIdAndStorageOwnershipAndStatusNotAndEmbeddingIsNull(
                galleryId,
                PhotoStorageOwnership.GALLERY,
                PhotoStatus.PENDING,
            )
        }

        // Mock 갤러리의 공유 샘플만 남아 있으면 Lambda 설정을 요구하거나 빈 작업을 호출하지
        // 않는다. 일반 갤러리의 기존 계약은 유지한다: 사진이 0장이어도 실행 설정이 없으면
        // 503을 돌려, 운영 설정 누락을 빈 작업으로 오인하지 않게 한다.
        if (gallery.galleryType == GalleryType.MOCK && targets == 0L) {
            log.info("임베딩 실행 대상 없음: galleryId={}, force={}", galleryId, force)
            return EmbeddingRunResponse(galleryId = galleryId, targets = 0)
        }

        // 기동이 아니라 여기서 실패한다. 로컬·테스트에는 실행기가 없는 것이 정상이라
        // 설정이 비어 있다고 앱을 못 뜨게 만들면 개발이 막힌다.
        if (!embeddingInvoker.isAvailable) {
            throw PhotoException(PhotoErrorCode.EMBEDDING_NOT_CONFIGURED)
        }

        embeddingInvoker.invoke(galleryId, force)

        log.info("임베딩 실행 요청: galleryId={}, force={}, 대상={}장", galleryId, force, targets)

        return EmbeddingRunResponse(galleryId = galleryId, targets = targets)
    }
}

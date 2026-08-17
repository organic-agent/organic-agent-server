package com.soma.wes.retouch.service

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.service.PhotoStorage
import com.soma.wes.retouch.domain.RetouchPhoto
import com.soma.wes.retouch.domain.RetouchRound
import com.soma.wes.retouch.domain.RetouchRoundStatus
import com.soma.wes.retouch.dto.request.AddRetouchPhotosRequest
import com.soma.wes.retouch.dto.request.UpdateRetouchPhotoRequest
import com.soma.wes.retouch.dto.response.IssueAnnotationUploadUrlResponse
import com.soma.wes.retouch.dto.response.RetouchOverviewResponse
import com.soma.wes.retouch.dto.response.RetouchPhotoResponse
import com.soma.wes.retouch.dto.response.RetouchRoundResponse
import com.soma.wes.retouch.dto.response.RetouchRoundSummaryResponse
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.exception.RetouchException
import com.soma.wes.retouch.repository.RetouchPhotoRepository
import com.soma.wes.retouch.repository.RetouchRoundRepository
import com.soma.wes.retouch.support.RetouchPhotoLoader
import com.soma.wes.retouch.support.RetouchViewAssembler
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime
import java.util.UUID

/**
 * 셀렉 확정 전에 부부가 보정을 요청하는 흐름. 보정사진을 모아 회차 단위로 일괄 제출한다.
 */
@Service
class RetouchService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val galleryRepository: GalleryRepository,
    private val retouchRoundRepository: RetouchRoundRepository,
    private val retouchPhotoRepository: RetouchPhotoRepository,
    private val retouchPhotoLoader: RetouchPhotoLoader,
    private val retouchViewAssembler: RetouchViewAssembler,
    private val photoStorage: PhotoStorage,
    private val properties: StorageProperties,
    private val clock: Clock,
) {

    companion object {

        /** 주석은 프론트 캔버스가 내보내는 투명 배경 레이어라 형식이 PNG 하나로 고정된다. */
        private const val ANNOTATION_CONTENT_TYPE = "image/png"

        private fun annotationKeyPrefix(galleryId: Long): String =
            "galleries/$galleryId/retouch/annotations/"
    }

    /**
     * 보정사진 페이지를 연다. 작가는 요청을 봐야 하고, 부부는 마감 뒤에도 결과를 봐야 하므로
     * 조회는 Viewer 문이다.
     */
    @Transactional(readOnly = true)
    fun get(galleryId: Long, userId: Long): RetouchOverviewResponse {
        val gallery = galleryAccessPolicy.requireViewer(galleryId, userId)

        return overviewOf(gallery)
    }

    /**
     * 보정사진을 담는다. 진행 중인 DRAFTING 회차가 없으면 첫 담기 때 만들어진다.
     */
    @Transactional
    fun addPhotos(galleryId: Long, userId: Long, request: AddRetouchPhotosRequest): RetouchOverviewResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        val gallery = galleryRepository.requireWithLockById(galleryId)
        val round = loadOrCreateDraftingRound(gallery)

        val photos = retouchPhotoLoader.loadPhotos(galleryId, request.photoIds)
        retouchPhotoLoader.validateNoneInRound(round.requiredId, request.photoIds)

        retouchPhotoRepository.saveAll(
            photos.map {
                RetouchPhoto(roundId = round.requiredId, galleryId = galleryId, photoId = it.requiredId)
            },
        )

        return overviewOf(gallery)
    }

    /**
     * 첫 담기 때 DRAFTING 회차가 만들어진다. 갤러리 행이 잠겨 있어 두 요청이 겹치지 않는다.
     * 이전 회차가 끝나기 전에는 새 회차를 열지 않는다 — 회차는 갤러리당 하나씩만 진행된다.
     */
    private fun loadOrCreateDraftingRound(gallery: Gallery): RetouchRound {
        val galleryId = gallery.requiredId
        retouchRoundRepository.findByGalleryIdAndStatus(galleryId, RetouchRoundStatus.DRAFTING)
            ?.let { return it }

        val latest = retouchRoundRepository.findFirstByGalleryIdOrderByRoundNoDesc(galleryId)
        if (latest != null && latest.status == RetouchRoundStatus.REQUESTED) {
            throw RetouchException(RetouchErrorCode.ROUND_IN_PROGRESS)
        }
        // 여기 오면 기존 회차는 전부 COMPLETED다 — 어차피 제출하지 못할 회차에 사진을 모으게
        // 두지 않는다. 최종 관문은 제출의 같은 검사다.
        validateWithinMaxRounds(gallery, submittedRoundCount = latest?.roundNo ?: 0)

        return retouchRoundRepository.save(
            RetouchRound(
                galleryId = galleryId,
                roundNo = latest?.roundNo?.plus(1) ?: RetouchRound.FIRST_ROUND_NO,
            ),
        )
    }

    private fun validateWithinMaxRounds(gallery: Gallery, submittedRoundCount: Int) {
        val max = gallery.maxRetouchRoundCount
        if (max != null && submittedRoundCount >= max) {
            throw RetouchException(RetouchErrorCode.MAX_RETOUCH_ROUND_COUNT_EXCEEDED)
        }
    }

    /**
     * DRAFTING 회차에서 한 장을 빼낸다. 없으면 404다.
     */
    @Transactional
    fun removePhoto(galleryId: Long, photoId: Long, userId: Long) {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        galleryRepository.requireWithLockById(galleryId)
        val round = requireDraftingRound(galleryId, RetouchErrorCode.PHOTO_NOT_IN_ROUND)

        if (retouchPhotoRepository.deleteByRoundIdAndPhotoId(round.requiredId, photoId) == 0L) {
            throw RetouchException(RetouchErrorCode.PHOTO_NOT_IN_ROUND)
        }
    }

    /**
     * 사진 한 장의 요청 텍스트와 주석 key를 저장한다. DRAFTING 동안에만, 덮어쓰기로 동작한다 —
     * 제출 뒤에는 DRAFTING 회차가 없어 404다.
     */
    @Transactional
    fun updatePhoto(
        galleryId: Long,
        photoId: Long,
        userId: Long,
        request: UpdateRetouchPhotoRequest,
    ): RetouchPhotoResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        // 항목 하나의 갱신이지만 갤러리 행을 잠근다 — 제출과 겹치면 잠긴 회차에 요청이 적힌다.
        galleryRepository.requireWithLockById(galleryId)
        val round = requireDraftingRound(galleryId, RetouchErrorCode.PHOTO_NOT_IN_ROUND)
        val item = retouchPhotoRepository.findByRoundIdAndPhotoId(round.requiredId, photoId)
            ?: throw RetouchException(RetouchErrorCode.PHOTO_NOT_IN_ROUND)

        validateAnnotationKey(galleryId, request.annotationKey)
        item.writeRequest(request.requestText, request.annotationKey)

        return retouchViewAssembler.toResponses(galleryId, listOf(item)).first()
    }

    private fun validateAnnotationKey(galleryId: Long, annotationKey: String?) {
        if (annotationKey != null && !annotationKey.startsWith(annotationKeyPrefix(galleryId))) {
            throw RetouchException(RetouchErrorCode.INVALID_ANNOTATION_KEY)
        }
    }

    /**
     * 주석 이미지가 올라갈 자리의 서명 URL을 발급한다. 사진과의 연결은 발급이 아니라
     * 요청 저장([updatePhoto])이 만든다 — 그래서 발급은 아무 행도 만들지 않는다.
     */
    @Transactional(readOnly = true)
    fun issueAnnotationUploadUrl(galleryId: Long, userId: Long): IssueAnnotationUploadUrlResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        val key = "${annotationKeyPrefix(galleryId)}${UUID.randomUUID()}.png"
        val presigned = photoStorage.presignUpload(key, ANNOTATION_CONTENT_TYPE)

        return IssueAnnotationUploadUrlResponse(
            annotationKey = key,
            uploadUrl = presigned.url,
            uploadUrlTtlSeconds = properties.uploadUrlTtl.seconds,
        )
    }

    /**
     * 회차를 제출한다. 이 시점의 요청들이 한 회차가 되고, 계약 횟수 한 번을 쓴다.
     */
    @Transactional
    fun submitRound(galleryId: Long, userId: Long): RetouchOverviewResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        val gallery = galleryRepository.requireWithLockById(galleryId)
        val round = requireDraftingRound(galleryId, RetouchErrorCode.EMPTY_ROUND)
        if (retouchPhotoRepository.countByRoundId(round.requiredId) == 0L) {
            throw RetouchException(RetouchErrorCode.EMPTY_ROUND)
        }

        // 회차를 만든 뒤 계약 횟수가 줄었을 수 있어 제출이 최종 관문이다.
        val submittedCount = retouchRoundRepository
            .countByGalleryIdAndStatusNot(galleryId, RetouchRoundStatus.DRAFTING)
        validateWithinMaxRounds(gallery, submittedRoundCount = submittedCount.toInt())

        round.submit(ZonedDateTime.now(clock))
        return overviewOf(gallery)
    }

    /** DRAFTING 회차가 없다는 것을 무엇으로 알릴지는 유스케이스마다 다르다 — 호출자가 정한다. */
    private fun requireDraftingRound(galleryId: Long, errorCode: RetouchErrorCode): RetouchRound =
        retouchRoundRepository.findByGalleryIdAndStatus(galleryId, RetouchRoundStatus.DRAFTING)
            ?: throw RetouchException(errorCode)

    private fun overviewOf(gallery: Gallery): RetouchOverviewResponse {
        val galleryId = gallery.requiredId
        val rounds = retouchRoundRepository.findAllByGalleryIdOrderByRoundNoAsc(galleryId)
        val photoCounts = retouchPhotoRepository.countAllByGalleryIdGroupByRoundId(galleryId)
            .associate { it.roundId to it.photoCount }

        val currentRound = rounds.lastOrNull { it.status != RetouchRoundStatus.COMPLETED }
        val currentItems = currentRound
            ?.let { retouchPhotoRepository.findAllByRoundId(it.requiredId) }
            .orEmpty()

        return RetouchOverviewResponse.of(
            maxRetouchRoundCount = gallery.maxRetouchRoundCount,
            submittedRoundCount = rounds.count { it.status != RetouchRoundStatus.DRAFTING },
            rounds = rounds.map { RetouchRoundSummaryResponse.of(it, photoCounts[it.requiredId] ?: 0L) },
            currentRound = currentRound?.let {
                RetouchRoundResponse.of(it, retouchViewAssembler.toResponses(galleryId, currentItems))
            },
            viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
        )
    }
}

package com.soma.wes.photo.support

import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.dto.PhotoViewDataDto
import com.soma.wes.photo.dto.response.PhotoMemoResponse
import com.soma.wes.photo.dto.response.PhotoResponse
import com.soma.wes.photo.repository.PhotoMemoRepository
import com.soma.wes.photo.repository.PhotoRatingRepository
import com.soma.wes.photo.service.port.PhotoStorage
import com.soma.wes.photo.service.port.PreviewCdn
import org.springframework.stereotype.Component

/**
 * 사진 엔티티를 화면이 그릴 수 있는 응답으로 만든다.
 *
 * 보기 주소는 둘 중 하나다(#336 ADR-0001). 부부·작가가 보는 미리보기는 CDN이 켜져 있으면 만료 없는 고정 주소 — 브라우저가 한 번 받은 사진을
 * 다시 받지 않는다. 그 밖(하객 · 미리보기 없는 사진 · CDN 꺼짐 · 요청이 `preview=signed`)은 지금처럼 만료 있는 서명 URL이다.
 */
@Component
class PhotoViewAssembler(
    private val photoStorage: PhotoStorage,
    private val previewCdn: PreviewCdn,
    private val previewDelivery: PreviewDelivery,
    private val photoRatingRepository: PhotoRatingRepository,
    private val photoMemoRepository: PhotoMemoRepository,
) {

    fun toResponse(photo: Photo): PhotoResponse = PhotoResponse.of(
        photo = photo,
        viewUrl = viewUrlOf(photo),
        score = scoreOf(photo),
        memo = memoOf(photo),
    )

    /**
     * 여러 장을 한 번에. 별점과 메모를 사진 수만큼 따로 읽지 않는다.
     * 한 페이지가 200장이면 [toResponse]를 반복하는 것만으로 질의가 200번 늘어난다.
     */
    fun toResponses(photos: List<Photo>): List<PhotoResponse> = toResponses(photos, viewDataOf(photos))

    /**
     * [viewDataOf]로 미리 읽어 둔 별점·메모에 보기 주소를 서명해 붙인다. DB를 읽지 않으므로 연결을 놓은 뒤 부를 수 있다 —
     * 갤러리 목록 한 페이지(200장)는 서명하는 동안 연결을 쥐고 있으면 그만큼 다른 요청이 풀 앞에서 기다린다(#286 (b)).
     */
    fun toResponses(photos: List<Photo>, viewData: PhotoViewDataDto): List<PhotoResponse> {
        prepareViewUrls(photos)
        return photos.map {
            PhotoResponse.of(
                photo = it,
                viewUrl = viewUrlOf(it),
                score = viewData.scores[it.requiredId],
                memo = viewData.memos[it.requiredId],
            )
        }
    }

    /** 응답에 붙일 별점·메모를 한 번씩 읽는다. 읽기 트랜잭션 안에서 부른다. */
    fun viewDataOf(photos: List<Photo>): PhotoViewDataDto = PhotoViewDataDto(scores = scoresOf(photos), memos = memosOf(photos))

    /** [viewDataOf]가 쓴다. 사진이 없으면 IN 절이 빈 채로 나가지 않도록 먼저 끊는다. */
    private fun scoresOf(photos: List<Photo>): Map<Long, Int> {
        if (photos.isEmpty()) {
            return emptyMap()
        }

        return photoRatingRepository.findAllByPhotoIdIn(photos.map { it.requiredId })
            .associate { it.photoId to it.score }
    }

    /** [viewDataOf]가 쓴다. [scoresOf]와 같이 빈 IN 절을 내보내지 않는다. */
    private fun memosOf(photos: List<Photo>): Map<Long, PhotoMemoResponse> {
        if (photos.isEmpty()) {
            return emptyMap()
        }

        return photoMemoRepository.findAllByPhotoIdIn(photos.map { it.requiredId })
            .associate { it.photoId to PhotoMemoResponse.from(it) }
    }

    /**
     * 협업 링크로 여는 화면이 쓴다. 별점과 메모를 아예 붙이지 않는다.
     *
     * 하객은 세션에 공유된 사진만 볼 수 있어서 갤러리 경로 전체를 여는 CDN 쿠키를 줄 수 없다 — 언제나 서명 URL이다(#336 03 Q2).
     */
    fun toAnonymousResponses(photos: List<Photo>): List<PhotoResponse> {
        prepareViewUrls(photos, signedOnly = true)
        return photos.map { PhotoResponse.of(photo = it, viewUrl = signedViewUrlOf(it), score = null) }
    }

    /**
     * 목록의 서명할 보기 주소를 한 번에 준비시킨다 — 서버 여러 대가 함께 쓰는 캐시를 사진마다가 아니라 한 번 읽게(#313).
     * CDN 고정 주소로 나갈 사진은 서명하지 않으니 뺀다.
     */
    private fun prepareViewUrls(photos: List<Photo>, signedOnly: Boolean = false) {
        val keys = photos.filter { it.status != PhotoStatus.PENDING }
            .map { it.viewKey }
            .filter { signedOnly || !servedByCdn(it) }
        photoStorage.prepareViews(keys)
    }

    /**
     * 응답에 실은 주소 중 만료되는 것이 있는가 — 응답의 `viewUrlsExpire`(#336 계약). 웹은 true일 때만 만료 전에 목록을 다시 읽는다.
     * CDN 고정 주소만 있으면 다시 읽을 까닭이 없다. 보정 결과처럼 이 조립자 밖에서 서명한 주소도 함께 넘겨 판정한다.
     */
    fun viewUrlsExpire(urls: Collection<String?>): Boolean = urls.any { it != null && !previewCdn.isFixedUrl(it) }

    /**
     * 이 사진에 매겨진 점수. 아무도 매기지 않았으면 null이다.
     */
    fun scoreOf(photo: Photo): Int? = photoRatingRepository.findByPhotoId(photo.requiredId)?.score

    /**
     * 이 사진의 메모. 아무도 적지 않았으면 null이다.
     */
    fun memoOf(photo: Photo): PhotoMemoResponse? =
        photoMemoRepository.findByPhotoId(photo.requiredId)?.let { PhotoMemoResponse.from(it) }

    /**
     * 부부·작가 화면에 그릴 URL. 파생본이 있으면 그쪽이고, 없으면 원본이다. 파생본이고 CDN이 켜져 있으면 고정 주소다.
     */
    fun viewUrlOf(photo: Photo): String? = presignIfUploaded(photo) {
        if (servedByCdn(photo.viewKey)) previewCdn.urlOf(photo.viewKey) else photoStorage.presignView(photo.viewKey)
    }

    /** 이 요청에서 이 키를 CDN 고정 주소로 내주는가. 웹이 `preview=signed`로 물러났으면 아니다. */
    private fun servedByCdn(key: String): Boolean = previewCdn.serves(key) && !previewDelivery.isSignedForced()

    /** 하객 화면처럼 언제나 서명 URL이어야 하는 곳. */
    private fun signedViewUrlOf(photo: Photo): String? = presignIfUploaded(photo) { photoStorage.presignView(photo.viewKey) }

    /**
     * 원본을 원래 크기로 여는 URL. 상세 화면이 쓴다.
     */
    fun originalUrlOf(photo: Photo): String? =
        presignIfUploaded(photo) { photoStorage.presignOriginal(photo.storageKey) }

    /** PENDING 판정을 한곳에 둔다. 두 URL이 같은 규칙을 따라야 화면이 둘을 나란히 쓸 수 있다. */
    private fun presignIfUploaded(photo: Photo, presign: () -> String): String? =
        if (photo.status == PhotoStatus.PENDING) null else presign()
}

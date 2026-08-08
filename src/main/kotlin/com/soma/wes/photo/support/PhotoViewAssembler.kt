package com.soma.wes.photo.support

import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.dto.response.PhotoResponse
import com.soma.wes.photo.service.PhotoStorage
import org.springframework.stereotype.Component

/**
 * 사진 엔티티를 화면이 그릴 수 있는 응답으로 만든다.
 *
 * 사진 목록·상세·클러스터·폴더가 모두 같은 규칙으로 URL을 붙여야 해서 한곳에 둔다. 규칙이 여러
 * 군데로 흩어지면 어느 한 곳만 PENDING 처리를 빠뜨려도 그 화면에서만 깨진 이미지가 나온다.
 */
@Component
class PhotoViewAssembler(
    private val photoStorage: PhotoStorage,
) {

    fun toResponse(photo: Photo): PhotoResponse = PhotoResponse.of(
        photo = photo,
        viewUrl = viewUrlOf(photo),
    )

    fun toResponses(photos: List<Photo>): List<PhotoResponse> = photos.map(::toResponse)

    /**
     * 화면에 그릴 URL. 파생본이 있으면 그쪽이고, 없으면 원본이다.
     *
     * PENDING은 URL만 발급되고 실제 객체는 아직 없을 수 있다. URL을 주면 프론트의 `<img>`가
     * 깨진 이미지를 그리므로, 올라온 것이 확실한 사진에만 채운다.
     */
    fun viewUrlOf(photo: Photo): String? = presignIfUploaded(photo) { photoStorage.presignView(photo.viewKey) }

    /**
     * 원본을 원래 크기로 여는 URL. 상세 화면이 쓴다.
     *
     * [viewUrlOf]와 달리 파생본으로 갈아타지 않는다 — 파생본은 긴 변을 줄인 JPEG라 확대하면
     * 뭉개진다. 대신 원본은 HEIC일 수 있어 브라우저가 못 그리므로, 화면은 둘 다 받아 고른다.
     */
    fun originalUrlOf(photo: Photo): String? =
        presignIfUploaded(photo) { photoStorage.presignOriginal(photo.storageKey) }

    /** PENDING 판정을 한곳에 둔다. 두 URL이 같은 규칙을 따라야 화면이 둘을 나란히 쓸 수 있다. */
    private fun presignIfUploaded(photo: Photo, presign: () -> String): String? =
        if (photo.status == PhotoStatus.PENDING) null else presign()
}

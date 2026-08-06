package com.soma.wes.photo.support

import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.dto.response.PhotoResponse
import com.soma.wes.photo.service.PhotoStorage
import org.springframework.stereotype.Component

/**
 * 사진 엔티티를 화면이 그릴 수 있는 응답으로 만든다.
 *
 * 사진 목록·클러스터·폴더가 모두 같은 규칙으로 URL을 붙여야 해서 한곳에 둔다. 규칙이 세 군데로
 * 흩어지면 어느 한 곳만 PENDING 처리를 빠뜨려도 그 화면에서만 깨진 이미지가 나온다.
 */
@Component
class PhotoViewAssembler(
    private val photoStorage: PhotoStorage,
) {

    fun toResponse(photo: Photo): PhotoResponse = PhotoResponse.of(
        photo = photo,
        // PENDING은 URL만 발급되고 실제 객체는 아직 없을 수 있다. URL을 주면 프론트의
        // <img>가 깨진 이미지를 그리므로, 올라온 것이 확실한 사진에만 채운다.
        //
        // 서명 대상은 원본이 아니라 viewKey다 — 파생본이 있으면 그쪽을 준다.
        viewUrl = if (photo.status == PhotoStatus.PENDING) null else photoStorage.presignView(photo.viewKey),
    )

    fun toResponses(photos: List<Photo>): List<PhotoResponse> = photos.map(::toResponse)
}

package com.soma.wes.gallery.dto.response

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.domain.GalleryType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "갤러리")
data class GalleryResponse(
    val id: Long,
    val studioId: Long,
    val title: String,
    val galleryType: GalleryType,

    @field:Schema(description = "Mock 갤러리가 복제한 샘플 템플릿 버전. 일반 갤러리는 null")
    val templateVersion: String?,

    val status: GalleryStatus,
    val selectionDeadline: ZonedDateTime?,

    @field:Schema(description = "부부가 최종적으로 고를 사진 장수. null이면 제한이 없다")
    val targetPhotoCount: Int?,

    val createdAt: ZonedDateTime?,
) {

    companion object {
        fun from(gallery: Gallery) = GalleryResponse(
            id = checkNotNull(gallery.id) { "저장되지 않은 갤러리는 응답할 수 없습니다." },
            studioId = gallery.studioId,
            title = gallery.title,
            galleryType = gallery.galleryType,
            templateVersion = gallery.templateVersion,
            status = gallery.status,
            selectionDeadline = gallery.selectionDeadline,
            targetPhotoCount = gallery.targetPhotoCount,
            createdAt = gallery.createdAt,
        )
    }
}

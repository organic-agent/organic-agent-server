package com.soma.wes.retouch.dto

import com.soma.wes.retouch.domain.RetouchPoint

/** DB 트랜잭션을 닫은 뒤 S3와 PDF 작업에 사용하는 불변 값이다. 엔티티나 서명 URL은 포함하지 않는다. */
data class RetouchPdfSnapshotDto(val galleryTitle: String, val photos: List<RetouchPdfPhotoDto>)

data class RetouchPdfPhotoDto(
    val photoId: Long,
    val originalFileName: String,
    val previewKey: String,
    val requestText: String?,
    val points: List<RetouchPoint>,
)

data class RetouchPdfDownloadDto(val filename: String, val bytes: ByteArray)

data class RetouchPdfBlockDto(val number: Int?, val lines: List<String>)

/** PDF의 실제 표시 이미지 영역. 웹의 왼쪽 위 원점을 PDF의 왼쪽 아래 원점으로 바꾼다. */
data class RetouchPdfImageRectDto(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun pin(point: RetouchPoint): Pair<Float, Float> =
        (x + point.x.toFloat() * width) to (y + (1f - point.y.toFloat()) * height)

    companion object {
        fun contain(width: Int, height: Int, box: RetouchPdfImageRectDto): RetouchPdfImageRectDto {
            require(width > 0 && height > 0)
            val scale = minOf(box.width / width, box.height / height)
            val renderedWidth = width * scale
            val renderedHeight = height * scale
            return RetouchPdfImageRectDto(
                x = box.x + (box.width - renderedWidth) / 2,
                y = box.y + (box.height - renderedHeight) / 2,
                width = renderedWidth,
                height = renderedHeight,
            )
        }
    }
}

/** 다운로드 모달에서 고르는 사진 범위. 저장된 회차 상태를 바꾸지 않는다. */
enum class RetouchPdfScopeDto(val value: String) { ALL("all"), NO_RESULT("noResult"), MEMO("memo") }

package com.soma.wes.retouch.dto

import com.soma.wes.retouch.domain.RetouchPoint

/** PDF의 실제 표시 이미지 영역. 웹의 왼쪽 위 원점을 PDF의 왼쪽 아래 원점으로 바꾼다. */
data class RetouchPdfImageRectDto(val x: Float, val y: Float, val width: Float, val height: Float) {
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

    fun pin(point: RetouchPoint): Pair<Float, Float> =
        (x + point.x.toFloat() * width) to (y + (1f - point.y.toFloat()) * height)
}

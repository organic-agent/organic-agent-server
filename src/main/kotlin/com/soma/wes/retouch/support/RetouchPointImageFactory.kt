package com.soma.wes.retouch.support

import com.soma.wes.photo.service.port.PreviewImageReader
import com.soma.wes.photo.support.JpegResizer
import com.soma.wes.retouch.dto.RetouchPointImagesDto
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.springframework.stereotype.Component

/**
 * 탭 지점을 모델이 볼 수 있게 그린다 — 빨간 원을 찍은 사진 전체와, 그 주변을 확대한 크롭 한 장.
 *
 * 좌표를 숫자로만 넘기면 모델은 거기 무엇이 있는지 알 수 없다. 전체 사진은 맥락(누가 신랑·신부인지,
 * 사진 어디인지)을, 크롭은 원 안의 세부를 준다. 두 장 조합은 2026-09-11 실측에서 대상 특정이 되는 것을
 * 확인한 형태이고, 크기·비율 상수는 그 실측(organic-agent-report `research/retouch-request/eval/imaging.py`)과 같다.
 *
 * 빨간 원이 보정 대상으로 오인되지 않게 하는 것은 프롬프트의 몫이다([RetouchRefinePrompt]).
 */
@Component
class RetouchPointImageFactory(
    private val previewImageReader: PreviewImageReader,
    private val jpegResizer: JpegResizer,
) {

    /** [x]·[y]가 null이면 사진 전체 메모다 — 마커도 크롭도 없이 전체 사진만 준다. */
    fun create(previewKey: String, x: Double?, y: Double?): RetouchPointImagesDto {
        val source = jpegResizer.decode(previewImageReader.read(previewKey))
        if (x == null || y == null) {
            return RetouchPointImagesDto(full = jpegResizer.toJpeg(source, FULL_LONG_EDGE), crop = null)
        }

        val pointX = x * source.width
        val pointY = y * source.height
        return RetouchPointImagesDto(
            full = jpegResizer.toJpeg(markedFull(source, pointX, pointY), FULL_LONG_EDGE),
            crop = jpegResizer.toJpeg(markedCrop(source, pointX, pointY)),
        )
    }

    private fun markedFull(source: BufferedImage, pointX: Double, pointY: Double): BufferedImage {
        val full = copyOf(source)
        drawRing(full, pointX, pointY, max(source.width, source.height) * FULL_RING_RATIO, FULL_RING_STROKE)
        return full
    }

    private fun markedCrop(source: BufferedImage, pointX: Double, pointY: Double): BufferedImage {
        val side = min(source.width, source.height) * CROP_RATIO
        val left = clamp(pointX - side / 2, source.width - side)
        val top = clamp(pointY - side / 2, source.height - side)

        val crop = BufferedImage(CROP_EDGE, CROP_EDGE, BufferedImage.TYPE_INT_RGB)
        val graphics = crop.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.drawImage(
            source.getSubimage(left.roundToInt(), top.roundToInt(), side.roundToInt(), side.roundToInt()),
            0, 0, CROP_EDGE, CROP_EDGE, null,
        )
        graphics.dispose()

        val scale = CROP_EDGE / side
        drawRing(crop, (pointX - left) * scale, (pointY - top) * scale, CROP_EDGE * CROP_RING_RATIO, CROP_RING_STROKE)
        return crop
    }

    /** 크롭이 사진 밖으로 나가지 않게 안쪽으로 민다 — 가장자리를 탭해도 한 변 길이는 그대로다. */
    private fun clamp(value: Double, upperBound: Double) = min(max(value, 0.0), max(upperBound, 0.0))

    private fun copyOf(source: BufferedImage): BufferedImage {
        val copy = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val graphics = copy.createGraphics()
        graphics.drawImage(source, 0, 0, null)
        graphics.dispose()
        return copy
    }

    private fun drawRing(image: BufferedImage, centerX: Double, centerY: Double, radius: Double, stroke: Float) {
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.color = Color.RED
        graphics.stroke = BasicStroke(stroke)
        graphics.drawOval(
            (centerX - radius).roundToInt(),
            (centerY - radius).roundToInt(),
            (radius * 2).roundToInt(),
            (radius * 2).roundToInt(),
        )
        graphics.dispose()
    }

    companion object {

        /** 실측이 쓴 크기. 전체 1024px + 크롭 512px이면 입력이 2,500토큰 안팎이다. */
        private const val FULL_LONG_EDGE = 1024
        private const val CROP_EDGE = 512

        /** 크롭 한 변 = 짧은 변의 35%. 대상 하나가 크롭을 채우면서 주변 맥락도 조금 남는 비율이다. */
        private const val CROP_RATIO = 0.35

        private const val FULL_RING_RATIO = 0.025
        private const val FULL_RING_STROKE = 4f
        private const val CROP_RING_RATIO = 0.06
        private const val CROP_RING_STROKE = 3f
    }
}

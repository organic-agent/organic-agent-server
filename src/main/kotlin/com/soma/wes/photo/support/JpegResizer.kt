package com.soma.wes.photo.support

import org.springframework.stereotype.Component
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 긴 변을 [longEdge]로 맞춘 JPEG. AI repo `llm.jpeg_bytes`(PIL LANCZOS, quality 80)의 자리다.
 * 미리보기는 임베더가 만들 때 이미 EXIF 회전이 반영돼 있어 여기서는 다시 돌리지 않는다.
 */
@Component
class JpegResizer {

    fun resize(source: ByteArray, longEdge: Int): ByteArray {
        val image = ImageIO.read(source.inputStream()) ?: throw IllegalArgumentException("디코딩할 수 없는 이미지")
        val scale = longEdge.toDouble() / max(image.width, image.height)
        val target = if (scale < 1.0) scaled(image, scale) else asRgb(image)
        return encode(target)
    }

    private fun scaled(image: BufferedImage, scale: Double): BufferedImage {
        val width = max(1, (image.width * scale).roundToInt())
        val height = max(1, (image.height * scale).roundToInt())
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = out.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.drawImage(image, 0, 0, width, height, null)
        graphics.dispose()
        return out
    }

    private fun asRgb(image: BufferedImage): BufferedImage {
        if (image.type == BufferedImage.TYPE_INT_RGB) return image
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val graphics = out.createGraphics()
        graphics.drawImage(image, 0, 0, null)
        graphics.dispose()
        return out
    }

    private fun encode(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = JPEG_QUALITY
        }
        return ByteArrayOutputStream().use { buffer ->
            ImageIO.createImageOutputStream(buffer).use { output ->
                writer.output = output
                writer.write(null, IIOImage(image, null, null), param)
            }
            writer.dispose()
            buffer.toByteArray()
        }
    }

    companion object {
        private const val JPEG_QUALITY = 0.8f
    }
}

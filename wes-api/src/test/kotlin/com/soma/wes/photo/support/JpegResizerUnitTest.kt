package com.soma.wes.photo.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class JpegResizerUnitTest {

    private val resizer = JpegResizer()

    @Test
    fun `긴 변을 맞추고 작은 이미지는 키우지 않는다`() {
        // given
        val large = png(width = 1600, height = 900)
        val small = png(width = 400, height = 300)

        // when
        val shrunk = ImageIO.read(resizer.resize(large, longEdge = 768).inputStream())
        val kept = ImageIO.read(resizer.resize(small, longEdge = 768).inputStream())

        // then
        assertThat(shrunk.width).isEqualTo(768)
        assertThat(shrunk.height).isEqualTo(432)
        assertThat(kept.width).isEqualTo(400)
        assertThat(kept.height).isEqualTo(300)
    }

    private fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}

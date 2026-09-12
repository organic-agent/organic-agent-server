package com.soma.wes.retouch.support

import com.soma.wes.photo.service.port.PreviewImageReader
import com.soma.wes.photo.support.JpegResizer
import java.awt.Color
import java.awt.image.BufferedImage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test

class RetouchPointImageFactoryUnitTest {

    private val resizer = JpegResizer()
    private val factory = RetouchPointImageFactory(StubReader(jpeg(1600, 1200)), resizer)

    @Test
    fun `탭 지점이 있으면 전체 사진과 크롭 두 장을 만든다`() {
        // when
        val images = factory.create(PREVIEW_KEY, x = 0.5, y = 0.5)

        // then
        val crop = resizer.decode(images.crop!!)
        assertSoftly { softly ->
            softly.assertThat(resizer.decode(images.full).width).isEqualTo(1024)
            softly.assertThat(crop.width).isEqualTo(512)
            softly.assertThat(crop.height).isEqualTo(512)
        }
    }

    @Test
    fun `사진 전체 메모는 크롭 없이 전체 사진만 만든다`() {
        // when
        val images = factory.create(PREVIEW_KEY, x = null, y = null)

        // then
        assertThat(images.crop).isNull()
    }

    @Test
    fun `가장자리를 탭해도 크롭이 사진 밖으로 나가지 않는다`() {
        // when — 네 모서리
        val corners = listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0)

        // then
        assertSoftly { softly ->
            corners.forEach { (x, y) ->
                val crop = resizer.decode(factory.create(PREVIEW_KEY, x, y).crop!!)
                softly.assertThat(crop.width).describedAs("($x, $y) 크롭 너비").isEqualTo(512)
                softly.assertThat(crop.height).describedAs("($x, $y) 크롭 높이").isEqualTo(512)
            }
        }
    }

    @Test
    fun `탭한 자리에 빨간 마커를 그린다`() {
        // given — 흰 사진이라 빨간 픽셀은 마커뿐이다
        val images = factory.create(PREVIEW_KEY, x = 0.5, y = 0.5)

        // when
        val full = resizer.decode(images.full)
        val reds = (0 until full.width).sumOf { x ->
            (0 until full.height).count { y -> isRed(full.getRGB(x, y)) }
        }

        // then
        assertThat(reds).isPositive()
    }

    private fun isRed(rgb: Int): Boolean {
        val color = Color(rgb)
        return color.red > 150 && color.green < 100 && color.blue < 100
    }

    private class StubReader(private val jpeg: ByteArray) : PreviewImageReader {
        override fun readJpeg(previewKey: String, longEdge: Int) = jpeg
        override fun read(previewKey: String) = jpeg
    }

    companion object {
        private const val PREVIEW_KEY = "previews/galleries/1/a.jpg"

        private fun jpeg(width: Int, height: Int): ByteArray {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.dispose()
            return JpegResizer().toJpeg(image)
        }
    }
}

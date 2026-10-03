package com.soma.wes.retouch.support

import com.soma.wes.photo.service.port.PreviewImageReader
import com.soma.wes.retouch.config.RetouchPdfProperties
import com.soma.wes.retouch.domain.RetouchPoint
import com.soma.wes.retouch.dto.RetouchPdfPhotoDto
import com.soma.wes.retouch.dto.RetouchPdfSnapshotDto
import com.soma.wes.retouch.dto.RetouchPdfImageRectDto
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.exception.RetouchException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.PDFTextStripperByArea
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class RetouchPdfRendererUnitTest {
    private val reader = object : PreviewImageReader {
        override fun readJpeg(previewKey: String, longEdge: Int) = read(previewKey)
        override fun read(previewKey: String): ByteArray = ByteArrayOutputStream().use { output ->
            val image = BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = Color(230, 236, 244)
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.dispose()
            ImageIO.write(image, "jpeg", output)
            output.toByteArray()
        }
    }

    @Test
    fun `세로 사진의 여백을 제외하고 핀 좌표의 원점을 변환한다`() {
        // given
        val rect = RetouchPdfImageRectDto.contain(800, 1200, RetouchPdfImageRectDto(x = 10f, y = 20f, width = 400f, height = 400f))
        // when & then
        assertThat(rect.width).isCloseTo(266.6667f, within(.001f))
        assertThat(rect.x).isCloseTo(76.6667f, within(.001f))
        assertThat(rect.pin(RetouchPoint(x = 0.0, y = 0.0, text = "위쪽")).second).isEqualTo(420f)
        assertThat(rect.pin(RetouchPoint(x = 1.0, y = 1.0, text = "아래쪽")).second).isEqualTo(20f)
    }

    @Test
    fun `긴 요청을 빠짐없이 이어 쓰고 번호와 사진을 다음 페이지에 반복한다`() {
        // given
        val repeated = "피부 질감을 유지해 주세요. ".repeat(110) + "마지막 요청 확인 😊"
        val snapshot = snapshot(listOf(
            RetouchPoint(x = 0.0, y = 0.0, text = repeated),
            RetouchPoint(x = 1.0, y = 1.0, text = "오른쪽 아래 요청"),
        ))
        // when
        val bytes = RetouchPdfRenderer(reader, RetouchPdfProperties()).render(snapshot)
        // then
        val output = Path.of("build/retouch-pdf-preview/edge-cases.pdf")
        Files.createDirectories(output.parent)
        Files.write(output, bytes)
        Loader.loadPDF(bytes).use { document ->
            assertThat(document.numberOfPages).isGreaterThan(1)
            val text = PDFTextStripper().getText(document)
            assertThat(text.replace(Regex("\\s"), "")).contains("마지막요청확인[U+1F60A]")
            assertThat(text).contains("이어짐", "오른쪽 아래 요청").doesNotContain("WES", "1차 보정", "PoC")
            val body = document.pages.joinToString("") { page ->
                val extractor = PDFTextStripperByArea()
                extractor.addRegion("requests", Rectangle(490, 140, 320, 395))
                extractor.extractRegions(page)
                assertThat(page.resources.xObjectNames.toList()).hasSize(1)
                assertThat(page.resources.fontNames.all { page.resources.getFont(it).isEmbedded }).isTrue()
                extractor.getTextForRegion("requests").lines()
                    .filterNot { it.contains("위치 1") || it.contains("위치 2") || it.trim().matches(Regex("[0-9]+")) }
                    .joinToString("")
            }.replace(Regex("\\s"), "")
            assertThat(Regex("피부질감을유지해주세요\\.").findAll(body).count()).isEqualTo(110)
        }
    }

    @Test
    fun `페이지와 바이트 상한은 일부 파일을 반환하는 대신 거절한다`() {
        // given
        val snapshot = snapshot(List(100) { RetouchPoint(x = .5, y = .5, text = "보정 요청") })
        // when & then
        for (properties in listOf(RetouchPdfProperties(maxPages = 1), RetouchPdfProperties(maxOutputBytes = 100))) {
            assertThatThrownBy { RetouchPdfRenderer(reader, properties).render(snapshot) }
                .isInstanceOf(RetouchException::class.java).extracting("errorCode")
                .isEqualTo(RetouchErrorCode.PDF_LIMIT_EXCEEDED)
        }
    }

    private fun snapshot(points: List<RetouchPoint>) = RetouchPdfSnapshotDto(
        galleryTitle = "샘플 웨딩 갤러리",
        photos = listOf(RetouchPdfPhotoDto(
            photoId = 55L, originalFileName = "SAMPLE.jpg", previewKey = "previews/sample.jpg",
            requestText = "사진 전체 밝기 요청", points = points,
        )),
    )
}

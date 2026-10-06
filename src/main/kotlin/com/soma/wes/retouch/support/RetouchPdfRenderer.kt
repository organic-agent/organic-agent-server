package com.soma.wes.retouch.support

import java.awt.Color
import com.soma.wes.photo.service.port.PreviewImageReader
import com.soma.wes.retouch.config.RetouchPdfProperties
import com.soma.wes.retouch.dto.RetouchPdfSnapshotDto
import com.soma.wes.retouch.dto.RetouchPdfPhotoDto
import com.soma.wes.retouch.dto.RetouchPdfImageRectDto
import com.soma.wes.retouch.dto.RetouchPdfBlockDto
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.exception.RetouchException
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.text.Normalizer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject

@Component
/** A4 가로 페이지에 사진 전체와 번호 핀을 배치하고, 요청 목록만 다음 페이지로 이어 쓴다. */
class RetouchPdfRenderer(
    private val photos: PreviewImageReader,
    private val properties: RetouchPdfProperties,
) {
    fun render(round: RetouchPdfSnapshotDto): ByteArray {
        val items = round.photos
        val started = System.nanoTime()
        return PDDocument().use { document ->
            val regular = loadFont(document, "NanumGothic-Regular.ttf")
            val bold = loadFont(document, "NanumGothic-Bold.ttf")
            document.documentInformation.title = "${round.galleryTitle} 요청서"
            drawRoundRequest(document = document, round = round, regular = regular, bold = bold, started = started)
            var imageBytes = 0L
            for ((photoIndex, item) in items.withIndex()) {
                checkBudget(document.numberOfPages, started)
                val jpeg = photos.readJpeg(item.previewKey, properties.imageLongEdge)
                checkBudget(document.numberOfPages, started)
                imageBytes += jpeg.size
                if (imageBytes > properties.maxOutputBytes) throw RetouchException(RetouchErrorCode.PDF_LIMIT_EXCEEDED)
                val pdfImage = JPEGFactory.createFromByteArray(document, jpeg)
                val rect = RetouchPdfImageRectDto.contain(width = pdfImage.width, height = pdfImage.height, box = IMAGE_BOX)
                val pending = ArrayDeque(blocks(item, regular))
                var continuation = 0
                do {
                    checkBudget(document.numberOfPages, started)
                    val page = PDPage(PAGE_SIZE)
                    document.addPage(page)
                    PDPageContentStream(document, page).use { stream ->
                        drawPage(
                            stream = stream, round = round, item = item,
                            photoIndex = photoIndex, photoCount = items.size, continuation = continuation,
                            pageNo = document.numberOfPages, image = pdfImage, rect = rect, regular = regular, bold = bold,
                        )
                        var cursorY = REQUEST_TOP
                        while (pending.isNotEmpty()) {
                            val block = pending.removeFirst()
                            val capacity = ((cursorY - TITLE_SPACE - BODY_BOTTOM) / LEADING).toInt()
                            if (capacity < 2) {
                                pending.addFirst(block)
                                break
                            }
                            val lines = block.lines.take(capacity)
                            if (block.number != null) drawPin(stream = stream, number = block.number, x = LIST_PIN_X, y = cursorY - LIST_PIN_OFFSET, radius = LIST_PIN_RADIUS, font = bold)
                            drawText(stream = stream, value = if (block.number == null) "전체" else "위치 ${block.number}", x = LIST_TITLE_X, y = cursorY - TITLE_BASELINE_OFFSET, font = bold, size = LABEL_SIZE, color = INK)
                            cursorY -= BLOCK_TITLE_SPACE
                            for (line in lines) {
                                drawText(stream = stream, value = line, x = REQUEST_X, y = cursorY, font = regular, size = BODY_SIZE, color = INK)
                                cursorY -= LEADING
                            }
                            if (lines.size < block.lines.size) {
                                pending.addFirst(block.copy(lines = block.lines.drop(lines.size)))
                                break
                            }
                            cursorY -= BLOCK_BOTTOM_SPACE
                            stream.setStrokingColor(BORDER)
                            stream.setLineWidth(SEPARATOR_WIDTH)
                            stream.moveTo(REQUEST_X, cursorY + SEPARATOR_OFFSET)
                            stream.lineTo(CONTENT_RIGHT, cursorY + SEPARATOR_OFFSET)
                            stream.stroke()
                            // 다음 제목과 번호 원이 구분선에 닿지 않도록 아래 여백을 둔다.
                            cursorY -= SEPARATOR_GAP
                        }
                    }
                    continuation++
                } while (pending.isNotEmpty())
            }
            RetouchPdfOutputStream(properties.maxOutputBytes).use { output ->
                document.save(output)
                checkTime(started)
                output.toByteArray()
            }
        }
    }

    /** 회차 전체 요청은 사진 페이지 앞에 따로 둔다 — 사진 페이지의 요청 칸은 사진 한 장의 것이라 섞으면 어느 사진의 말인지 흐려진다. */
    private fun drawRoundRequest(document: PDDocument, round: RetouchPdfSnapshotDto, regular: PDType0Font, bold: PDType0Font, started: Long) {
        val text = round.roundRequestText?.takeIf { it.isNotBlank() } ?: return
        val pending = ArrayDeque(wrap(text, regular, ROUND_REQUEST_WIDTH))
        var continuation = 0
        while (pending.isNotEmpty()) {
            checkBudget(document.numberOfPages, started)
            val page = PDPage(PAGE_SIZE)
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                drawHeader(stream = stream, round = round, regular = regular, bold = bold)
                drawText(stream = stream, value = "전체 요청 · 모든 사진에 적용${if (continuation > 0) " · 이어짐" else ""}", x = MARGIN_X, y = CAPTION_Y, font = bold, size = CAPTION_SIZE, color = INK)
                var cursorY = REQUEST_TOP
                while (pending.isNotEmpty() && cursorY >= BODY_BOTTOM) {
                    drawText(stream = stream, value = pending.removeFirst(), x = MARGIN_X, y = cursorY, font = regular, size = BODY_SIZE, color = INK)
                    cursorY -= LEADING
                }
                drawText(stream = stream, value = "${document.numberOfPages} 페이지", x = FOOTER_X, y = FOOTER_Y, font = regular, size = FOOTER_SIZE, color = MUTED)
            }
            continuation++
        }
    }

    private fun loadFont(document: PDDocument, filename: String): PDType0Font =
        ClassPathResource("fonts/retouch/$filename").inputStream.use { PDType0Font.load(document, it) }

    private fun checkBudget(pages: Int, started: Long) {
        if (pages >= properties.maxPages) throw RetouchException(RetouchErrorCode.PDF_LIMIT_EXCEEDED)
        checkTime(started)
    }

    private fun checkTime(started: Long) {
        if (System.nanoTime() - started > properties.generationTimeout.toNanos()) {
            throw RetouchException(RetouchErrorCode.PDF_GENERATION_TIMEOUT)
        }
    }

    private fun blocks(item: RetouchPdfPhotoDto, font: PDType0Font): List<RetouchPdfBlockDto> = buildList {
        if (!item.requestText.isNullOrBlank()) add(RetouchPdfBlockDto(number = null, lines = wrap(item.requestText, font)))
        for ((index, point) in item.points.withIndex()) {
            add(RetouchPdfBlockDto(number = index + 1, lines = wrap(if (point.useRefinedText) point.refinedText.orEmpty() else point.text, font)))
        }
        if (isEmpty()) add(RetouchPdfBlockDto(number = null, lines = listOf("이 사진에는 별도 보정 요청이 없습니다.")))
    }

    private fun wrap(value: String, font: PDType0Font, width: Float = REQUEST_WIDTH): List<String> {
        val normalized = displayable(value.replace("\r\n", "\n").replace('\r', '\n'), font)
        val lines = mutableListOf<String>()
        for (paragraph in normalized.split('\n')) {
            var line = ""
            for (character in paragraph) {
                val next = line + character
                if (font.getStringWidth(next) * BODY_SIZE / FONT_UNITS > width && line.isNotEmpty()) {
                    lines.add(line)
                    line = character.toString()
                } else line = next
            }
            lines.add(line)
        }
        return lines
    }

    private fun drawPage(
        stream: PDPageContentStream, round: RetouchPdfSnapshotDto, item: RetouchPdfPhotoDto,
        photoIndex: Int, photoCount: Int, continuation: Int, pageNo: Int,
        image: PDImageXObject, rect: RetouchPdfImageRectDto, regular: PDType0Font, bold: PDType0Font,
    ) {
        drawHeader(stream = stream, round = round, regular = regular, bold = bold)
        drawText(stream = stream, value = "${photoIndex + 1} / $photoCount", x = COUNTER_X, y = COUNTER_Y, font = bold, size = COUNTER_SIZE, color = ACCENT)
        drawText(stream = stream, value = item.originalFileName, x = MARGIN_X, y = CAPTION_Y, font = bold, size = LABEL_SIZE, color = INK, maxWidth = FILENAME_WIDTH)
        drawText(stream = stream, value = "요청 사항 · 핀 ${item.points.size}개${if (continuation > 0) " · 이어짐" else ""}", x = REQUEST_X, y = CAPTION_Y, font = bold, size = CAPTION_SIZE, color = INK)
        stream.setNonStrokingColor(PANEL)
        stream.addRect(IMAGE_BOX.x, IMAGE_BOX.y, IMAGE_BOX.width, IMAGE_BOX.height); stream.fill()
        stream.drawImage(image, rect.x, rect.y, rect.width, rect.height)
        for ((index, point) in item.points.withIndex()) {
            val (x, y) = rect.pin(point)
            drawPin(stream = stream, number = index + 1, x = x, y = y, radius = PIN_RADIUS, font = bold)
        }
        drawText(stream = stream, value = "사진 ID ${item.photoId} · $pageNo 페이지", x = FOOTER_X, y = FOOTER_Y, font = regular, size = FOOTER_SIZE, color = MUTED)
    }

    private fun drawHeader(stream: PDPageContentStream, round: RetouchPdfSnapshotDto, regular: PDType0Font, bold: PDType0Font) {
        drawText(stream = stream, value = "보정 요청서", x = MARGIN_X, y = HEADER_Y, font = bold, size = BLOCK_TITLE_SPACE, color = INK)
        drawText(stream = stream, value = round.galleryTitle, x = SUBTITLE_X, y = SUBTITLE_Y, font = regular, size = PIN_RADIUS, color = MUTED, maxWidth = SUBTITLE_WIDTH)
        stream.setStrokingColor(BORDER)
        stream.moveTo(MARGIN_X, HEADER_LINE_Y); stream.lineTo(CONTENT_RIGHT, HEADER_LINE_Y); stream.stroke()
    }

    private fun drawPin(stream: PDPageContentStream, number: Int, x: Float, y: Float, radius: Float, font: PDType0Font) {
        val k = radius * BEZIER_CIRCLE
        stream.setNonStrokingColor(ACCENT)
        stream.setStrokingColor(Color.WHITE)
        stream.setLineWidth(PIN_BORDER_WIDTH)
        stream.moveTo(x + radius, y)
        stream.curveTo(x + radius, y + k, x + k, y + radius, x, y + radius)
        stream.curveTo(x - k, y + radius, x - radius, y + k, x - radius, y)
        stream.curveTo(x - radius, y - k, x - k, y - radius, x, y - radius)
        stream.curveTo(x + k, y - radius, x + radius, y - k, x + radius, y)
        stream.closePath(); stream.fillAndStroke()
        val label = number.toString()
        val size = if (number >= THREE_DIGIT_PIN) SMALL_PIN_FONT_SIZE else PIN_RADIUS
        val width = font.getStringWidth(label) * size / FONT_UNITS
        drawText(stream = stream, value = label, x = x - width / 2, y = y - size * PIN_BASELINE, font = font, size = size, color = Color.WHITE)
    }

    private fun drawText(
        stream: PDPageContentStream, value: String, x: Float, y: Float, font: PDType0Font,
        size: Float, color: Color, maxWidth: Float? = null,
    ) {
        var text = displayable(value, font).replace('\n', ' ')
        if (maxWidth != null) {
            while (text.length > 1 && font.getStringWidth(text) * size / FONT_UNITS > maxWidth) text = text.dropLast(2) + "…"
        }
        stream.beginText(); stream.setFont(font, size); stream.setNonStrokingColor(color)
        stream.newLineAtOffset(x, y); stream.showText(text); stream.endText()
    }

    // 글꼴에 없는 이모지 등을 삭제하지 않고 유니코드 코드로 남긴다.
    private fun displayable(value: String, font: PDType0Font): String = buildString {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
        for (codePoint in normalized.codePoints().toArray()) {
            when {
                codePoint == LINE_FEED -> append('\n')
                codePoint == TAB -> append("    ")
                codePoint < FIRST_PRINTABLE -> append("[U+${codePoint.toString(16).uppercase()}]")
                canEncode(font, codePoint) -> appendCodePoint(codePoint)
                else -> append("[U+${codePoint.toString(16).uppercase()}]")
            }
        }
    }

    private fun canEncode(font: PDType0Font, codePoint: Int): Boolean = try {
        font.encode(String(Character.toChars(codePoint)))
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    companion object {
        /** 검증한 A4 가로 PDF 레이아웃. 길이와 위치는 point 단위다. */
        private const val REQUEST_TOP = 447f
        private const val TITLE_SPACE = 24f
        private const val LIST_PIN_X = 502f
        private const val LIST_TITLE_X = 522f
        private const val REQUEST_X = 493f
        private const val CONTENT_RIGHT = 806f
        private const val BLOCK_TITLE_SPACE = 26f
        private const val BLOCK_BOTTOM_SPACE = 15f
        private const val SEPARATOR_GAP = 18f
        private const val REQUEST_WIDTH = 306f
        /** 전체 요청 페이지는 사진이 없어 본문이 좌우 여백 사이를 다 쓴다(CONTENT_RIGHT - MARGIN_X). */
        private const val ROUND_REQUEST_WIDTH = 770f
        private const val IMAGE_BOX_WIDTH = 396f
        private const val IMAGE_BOX_HEIGHT = 404f
        private const val IMAGE_BOX_X = 50f
        private const val IMAGE_BOX_Y = 63f
        private const val MARGIN_X = 36f
        private const val HEADER_Y = 548f
        private const val SUBTITLE_X = 37f
        private const val SUBTITLE_Y = 525f
        private const val COUNTER_X = 746f
        private const val COUNTER_Y = 550f
        private const val HEADER_LINE_Y = 508f
        private const val CAPTION_Y = 486f
        private const val FOOTER_X = 656f
        private const val FOOTER_Y = 32f
        private const val FILENAME_WIDTH = 418f
        private const val SUBTITLE_WIDTH = 600f
        private const val BEZIER_CIRCLE = 0.55228475f
        private const val PIN_BORDER_WIDTH = 1.6f
        private const val PIN_BASELINE = 0.36f
        private const val SMALL_PIN_FONT_SIZE = 7.5f
        private const val LIST_PIN_RADIUS = 9f
        private const val PIN_RADIUS = 10f
        private const val LABEL_SIZE = 11f
        private const val CAPTION_SIZE = 12f
        private const val COUNTER_SIZE = 17f
        private const val SEPARATOR_WIDTH = 0.5f
        private const val TITLE_BASELINE_OFFSET = 4f
        private const val LIST_PIN_OFFSET = 1f
        private const val SEPARATOR_OFFSET = 5f
        /** PDF footer 글자 크기(point). */
        private const val FOOTER_SIZE = 8.5f
        /** PDF 폰트 폭의 기준 단위(1000 glyph units). */
        private const val FONT_UNITS = 1000
        /** 세 자리 핀 번호는 원 안에 맞추어 글자를 줄인다. */
        private const val THREE_DIGIT_PIN = 100
        /** Unicode 제어 문자 경계. */
        private const val LINE_FEED = 10
        private const val TAB = 9
        private const val FIRST_PRINTABLE = 32

        private val PAGE_SIZE = PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)
        private val IMAGE_BOX = RetouchPdfImageRectDto(x = IMAGE_BOX_X, y = IMAGE_BOX_Y, width = IMAGE_BOX_WIDTH, height = IMAGE_BOX_HEIGHT)
        private val INK = Color(36, 39, 47)
        private val MUTED = Color(114, 119, 130)
        private val ACCENT = Color(110, 77, 175)
        private val BORDER = Color(226, 228, 235)
        private val PANEL = Color(247, 247, 250)
        private const val BODY_SIZE = 11f
        private const val LEADING = 17f
        private const val BODY_BOTTOM = 67f
    }
}

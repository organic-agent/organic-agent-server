package com.soma.wes.retouch.service

import com.soma.wes.retouch.config.RetouchPdfProperties
import com.soma.wes.retouch.dto.RetouchPdfDownloadDto
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.exception.RetouchException
import com.soma.wes.retouch.support.RetouchPdfRenderer
import com.soma.wes.retouch.support.RetouchPdfSnapshotLoader
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.IOException
import java.util.concurrent.Semaphore

/** 읽기 트랜잭션이 종료된 뒤 미리보기 읽기와 PDF 생성을 수행한다. 요청 내용을 변경하지 않는다. */
@Service
class RetouchPdfService(
    private val loader: RetouchPdfSnapshotLoader,
    private val renderer: RetouchPdfRenderer,
    properties: RetouchPdfProperties,
) {
    private val slots = Semaphore(properties.maxConcurrentGenerations)
    private val log = LoggerFactory.getLogger(javaClass)

    fun download(galleryId: Long, roundNo: Int, userId: Long, scope: String): RetouchPdfDownloadDto {
        val snapshot = loader.load(galleryId = galleryId, roundNo = roundNo, userId = userId, scope = scope)
        if (!slots.tryAcquire()) throw RetouchException(RetouchErrorCode.PDF_GENERATION_BUSY)
        try {
            val filename = snapshot.galleryTitle.filter { !it.isISOControl() && it != '/' && it != '\\' }.take(100)
            return RetouchPdfDownloadDto(filename = "$filename 요청서.pdf", bytes = renderer.render(snapshot))
        } catch (e: IOException) {
            log.error("PDF 생성 실패: galleryId={}, roundNo={}", galleryId, roundNo, e)
            throw RetouchException(RetouchErrorCode.PDF_GENERATION_FAILED)
        } finally {
            slots.release()
        }
    }
}

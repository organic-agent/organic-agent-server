package com.soma.wes.selection.service

import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.retouch.repository.RetouchPhotoRepository
import com.soma.wes.retouch.repository.RetouchRoundRepository
import com.soma.wes.retouch.service.RetouchRequestService
import com.soma.wes.selection.exception.SelectionErrorCode
import com.soma.wes.selection.exception.SelectionException
import com.soma.wes.selection.repository.PhotoSelectionItemRepository
import com.soma.wes.selection.repository.PhotoSelectionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/** 외부 업체 포맷 확정 전에도 파일명과 요청 내용을 빠짐없이 전달하는 UTF-8 CSV 계약. */
@Service
class PhotoSelectionExportService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val galleryRepository: GalleryRepository,
    private val selectionRepository: PhotoSelectionRepository,
    private val itemRepository: PhotoSelectionItemRepository,
    private val photoRepository: PhotoRepository,
    private val roundRepository: RetouchRoundRepository,
    private val retouchPhotoRepository: RetouchPhotoRepository,
    private val requestWriter: RetouchRequestService,
    private val clock: Clock,
) {
    @Transactional
    fun export(galleryId: Long, userId: Long): ByteArray {
        galleryAccessPolicy.requireParticipantViewer(galleryId, userId)
        val gallery = galleryRepository.requireWithLockById(galleryId)
        val selection = selectionRepository.findByGalleryId(galleryId)
            ?: throw SelectionException(SelectionErrorCode.EMPTY_SELECTION)
        val items = itemRepository.findAllBySelectionId(selection.requiredId)
        val photos = photoRepository.findAllByGalleryIdAndIdIn(galleryId, items.map { it.photoId }).associateBy { it.requiredId }
        selection.requireExactTarget(gallery.maxSelectablePhotoCount, photos.size)
        val orderedIds = items.sortedWith(compareBy({ it.sortOrder }, { it.requiredId }))
            .map { it.photoId }.filter { it in photos }
        var round = roundRepository.findFirstByGalleryIdOrderByRoundNoDesc(galleryId)
        if (galleryAccessPolicy.isPersonalGallery(galleryId) && (round == null || round.isDrafting)) {
            galleryAccessPolicy.requireRetouchRequester(galleryId, userId)
            round = requestWriter.submit(
                gallery = gallery,
                roundNo = 1,
                photoIds = orderedIds,
                requests = emptyList(),
                at = ZonedDateTime.now(clock),
            )
        }
        val notesByPhoto = round?.let { retouchPhotoRepository.findAllByRoundId(it.requiredId) }
            .orEmpty().associateBy { it.photoId }
        val rows = orderedIds.map { photoId ->
            val photo = photos.getValue(photoId)
            val note = notesByPhoto[photoId]
            val points = note?.points.orEmpty().mapIndexed { index, point ->
                "${index + 1} (${point.x},${point.y}): ${if (point.useRefinedText) point.refinedText else point.text}"
            }.joinToString("\n")
            listOf(photoId.toString(), photo.originalFileName, note?.requestText.orEmpty(), points)
                .joinToString(",", transform = ::csvCell)
        }
        return (CSV_BOM + "photo_id,filename,request,point_requests\r\n" + rows.joinToString("\r\n") + "\r\n")
            .toByteArray(Charsets.UTF_8)
    }

    private fun csvCell(value: String): String {
        // 스프레드시트에서 사용자 메모가 수식으로 실행되지 않게 텍스트로 고정한다.
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    companion object {
        /** Excel에서도 한글 UTF-8을 올바르게 감지하도록 붙인다. */
        private const val CSV_BOM = "\uFEFF"
    }
}

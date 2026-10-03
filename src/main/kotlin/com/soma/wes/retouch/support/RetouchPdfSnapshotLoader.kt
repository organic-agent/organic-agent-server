package com.soma.wes.retouch.support

import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.retouch.config.RetouchPdfProperties
import com.soma.wes.retouch.domain.RetouchPhoto
import com.soma.wes.retouch.domain.RetouchRoundStatus
import com.soma.wes.retouch.dto.RetouchPdfScopeDto
import com.soma.wes.retouch.dto.RetouchPdfPhotoDto
import com.soma.wes.retouch.dto.RetouchPdfSnapshotDto
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.exception.RetouchException
import com.soma.wes.retouch.repository.RetouchPhotoRepository
import com.soma.wes.retouch.repository.RetouchRoundRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** 회차 상세 조회와 동일한 공개 규칙으로 저장된 요청을 읽는다. S3 호출은 하지 않는다. */
@Service
class RetouchPdfSnapshotLoader(
    private val access: GalleryAccessPolicy,
    private val rounds: RetouchRoundRepository,
    private val items: RetouchPhotoRepository,
    private val photos: PhotoRepository,
    private val properties: RetouchPdfProperties,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun load(galleryId: Long, roundNo: Int, userId: Long, scope: String): RetouchPdfSnapshotDto {
        val gallery = access.requireViewer(galleryId, userId)
        val round = rounds.findByGalleryIdAndRoundNo(galleryId, roundNo)
            ?: throw RetouchException(RetouchErrorCode.ROUND_NOT_FOUND)
        val selectedScope = RetouchPdfScopeDto.entries.firstOrNull { it.value == scope }
            ?: throw RetouchException(RetouchErrorCode.INVALID_PDF_SCOPE)
        if (round.isDrafting && access.isStudioManager(galleryId, userId)) {
            throw RetouchException(RetouchErrorCode.EMPTY_PDF)
        }
        val includeResults = round.status == RetouchRoundStatus.COMPLETED ||
            access.canInspectRetouchDrafts(galleryId, userId)
        val targets = items.findAllByRoundId(round.requiredId).filter { item ->
            when (selectedScope) {
                RetouchPdfScopeDto.MEMO -> !item.requestText.isNullOrBlank() || item.points.isNotEmpty()
                RetouchPdfScopeDto.NO_RESULT -> !includeResults || !item.hasResult
                RetouchPdfScopeDto.ALL -> true
            }
        }
        if (targets.isEmpty()) throw RetouchException(RetouchErrorCode.EMPTY_PDF)
        if (targets.size > properties.maxPhotos) throw RetouchException(RetouchErrorCode.PDF_LIMIT_EXCEEDED)
        val byPhotoId = targets.associateBy { it.photoId }
        val ordered = photos.findAllByGalleryIdAndIdIn(galleryId, byPhotoId.keys).sortedWith(Photo.DISPLAY_ORDER)
        if (ordered.size != targets.size) throw RetouchException(RetouchErrorCode.PHOTO_NOT_IN_ROUND)
        val snapshot = ordered.map { photo ->
            val item = byPhotoId.getValue(photo.requiredId)
            if (item.points.size > RetouchPhoto.MAX_POINTS ||
                (item.requestText?.length ?: 0) > RetouchPhoto.MAX_REQUEST_TEXT_LENGTH
            ) throw RetouchException(RetouchErrorCode.PDF_LIMIT_EXCEEDED)
            for (point in item.points) point.validate()
            RetouchPdfPhotoDto(
                photoId = photo.requiredId,
                originalFileName = photo.originalFileName,
                previewKey = photo.previewKey ?: throw RetouchException(RetouchErrorCode.PDF_PREVIEW_NOT_READY),
                requestText = item.requestText,
                points = item.points.toList(),
            )
        }
        val characters = snapshot.sumOf { photo ->
            (photo.requestText?.length ?: 0).toLong() + photo.points.sumOf { point ->
                (if (point.useRefinedText) point.refinedText.orEmpty() else point.text).length.toLong()
            }
        }
        if (characters > properties.maxTextCharacters) throw RetouchException(RetouchErrorCode.PDF_LIMIT_EXCEEDED)
        return RetouchPdfSnapshotDto(galleryTitle = gallery.title, photos = snapshot)
    }

}

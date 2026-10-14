package com.soma.wes.photo.service

import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.global.page.PageRequests
import com.soma.wes.global.page.PageResponse
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoComment
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.dto.request.WritePhotoCommentRequest
import com.soma.wes.photo.dto.response.PhotoCommentResponse
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.repository.PhotoCommentRepository
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.user.repository.requireById
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

@Service
class PhotoCommentService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoRepository: PhotoRepository,
    private val commentRepository: PhotoCommentRepository,
    private val userRepository: UserRepository,
    private val clock: Clock,
    private val activityRecorder: ActivityRecorder,
) {
    @Transactional(readOnly = true)
    fun list(galleryId: Long, photoId: Long, userId: Long, page: Int, size: Int): PageResponse<PhotoCommentResponse> {
        galleryAccessPolicy.requireParticipantViewer(galleryId, userId)
        requireUploaded(photoRepository.findByIdAndGalleryId(photoId, galleryId))

        val found = commentRepository.findAllByPhotoId(photoId, PageRequests.of(page, size, COMMENT_ORDER))
        val authors = userRepository.findAllById(found.content.map { it.authorId }.distinct())
            .associateBy { it.requiredId }
        return PageResponse.of(found, found.content.map { comment ->
            PhotoCommentResponse.of(comment, authors[comment.authorId]?.nickname ?: DELETED_AUTHOR, userId)
        })
    }

    @Transactional
    fun write(
        galleryId: Long,
        photoId: Long,
        userId: Long,
        request: WritePhotoCommentRequest,
    ): PhotoCommentResponse {
        galleryAccessPolicy.requireParticipantWriter(galleryId, userId)
        requireUploaded(photoRepository.findWithLockByIdAndGalleryId(photoId, galleryId))

        val author = userRepository.requireById(userId)
        val comment = commentRepository.save(PhotoComment.of(photoId, author.requiredId, request.content))
        activityRecorder.recordGallery(galleryId)
        return PhotoCommentResponse.of(comment, author.nickname, userId)
    }

    @Transactional
    fun delete(galleryId: Long, photoId: Long, commentId: Long, userId: Long) {
        galleryAccessPolicy.requireParticipantViewer(galleryId, userId)
        requireUploaded(photoRepository.findWithLockByIdAndGalleryId(photoId, galleryId))

        val comment = commentRepository.findByIdAndPhotoId(commentId, photoId)
            ?: throw PhotoException(PhotoErrorCode.COMMENT_NOT_FOUND)
        comment.deleteBy(userId, ZonedDateTime.now(clock))
        activityRecorder.recordGallery(galleryId)
    }

    private fun requireUploaded(photo: Photo?) {
        if (photo == null || photo.status == PhotoStatus.PENDING) {
            throw PhotoException(PhotoErrorCode.PHOTO_NOT_FOUND)
        }
    }

    companion object {
        /** 같은 시각에 작성해도 순서가 바뀌지 않는 대화 순서. */
        private val COMMENT_ORDER = Sort.by(Sort.Direction.ASC, "id")

        /** 사용자 소프트 삭제로 조회되지 않는 작성자의 표시명. */
        private const val DELETED_AUTHOR = "탈퇴한 사용자"
    }
}

package com.soma.wes.photo.service

import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.domain.PhotoMemo
import com.soma.wes.photo.dto.request.WritePhotoMemoRequest
import com.soma.wes.photo.dto.response.PhotoMemoResponse
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.repository.PhotoMemoRepository
import com.soma.wes.photo.repository.PhotoRepository
import java.time.Clock
import java.time.ZonedDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 사진마다 하나인 메모. 지금은 개인 갤러리의 소유자와 파트너만 쓴다.
 *
 * 메모는 셀렉 중에도 보정 확인 중에도 적으므로 선택 마감·갤러리 공개 상태를 보지 않고, 보관된 갤러리에만 막는다.
 * 초대 갤러리의 부부로 넓힐 때는 작가가 같은 사진 응답을 받으므로 응답의 `memo`를 작가에게 가리는 일이 함께 필요하다.
 */
@Service
class PhotoMemoService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoRepository: PhotoRepository,
    private val photoMemoRepository: PhotoMemoRepository,
    private val activityRecorder: ActivityRecorder,
    private val clock: Clock,
) {

    /** 메모를 적는다. 같은 사진에 다시 부르면 덮어쓴다. */
    @Transactional
    fun write(
        galleryId: Long,
        photoId: Long,
        userId: Long,
        request: WritePhotoMemoRequest,
    ): PhotoMemoResponse {
        val gallery = galleryAccessPolicy.requirePersonalParticipant(galleryId, userId)
        gallery.requireWritable(ZonedDateTime.now(clock))

        // 첫 메모를 두 사람이 동시에 적으면 둘 다 INSERT로 가 유니크에 걸린다. 사진 행을 잠가 차례로 세운다.
        val photo = photoRepository.findWithLockByIdAndGalleryId(photoId, galleryId)
            ?: throw PhotoException(PhotoErrorCode.PHOTO_NOT_FOUND)

        val memo = photoMemoRepository.findByPhotoId(photo.requiredId)
            ?.apply { write(request.content, userId) }
            ?: photoMemoRepository.save(PhotoMemo.of(photo.requiredId, request.content, userId))

        activityRecorder.recordGallery(galleryId)
        return PhotoMemoResponse.from(memo)
    }

    /** 메모를 지운다. 적은 적 없는 사진에도 성공한다. */
    @Transactional
    fun clear(galleryId: Long, photoId: Long, userId: Long) {
        val gallery = galleryAccessPolicy.requirePersonalParticipant(galleryId, userId)
        gallery.requireWritable(ZonedDateTime.now(clock))

        val photo = photoRepository.findByIdAndGalleryId(photoId, galleryId)
            ?: throw PhotoException(PhotoErrorCode.PHOTO_NOT_FOUND)

        photoMemoRepository.deleteByPhotoId(photo.requiredId)
        activityRecorder.recordGallery(galleryId)
    }
}

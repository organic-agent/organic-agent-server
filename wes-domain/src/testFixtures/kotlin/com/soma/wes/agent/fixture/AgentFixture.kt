package com.soma.wes.agent.fixture

import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.service.CollabGuestService
import com.soma.wes.collab.service.CollabSessionService
import com.soma.wes.folder.fixture.FolderFixture
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.photo.dto.request.RatePhotoRequest
import com.soma.wes.photo.dto.request.WritePhotoCommentRequest
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.service.PhotoCommentService
import com.soma.wes.photo.service.PhotoRatingService
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class AgentFixture(
    private val galleryFixture: GalleryFixture,
    private val folderFixture: FolderFixture,
    private val photoFixture: PhotoFixture,
    private val photoRatingService: PhotoRatingService,
    private val photoCommentService: PhotoCommentService,
    private val collabSessionService: CollabSessionService,
    private val collabGuestService: CollabGuestService,
) {

    companion object {

        private val LANDSCAPE = 6000 to 4000
        private val PORTRAIT = 4000 to 6000
        private val DAY: LocalDateTime = LocalDateTime.of(2026, 5, 2, 0, 0)

        private val PHOTO_SPECS = listOf(
            PhotoSpec("bride", burstId = 1, burstRank = 0, landscape = false, takenAt = DAY.withHour(10)),
            PhotoSpec("bride", burstId = 1, burstRank = 1, landscape = false, takenAt = DAY.withHour(10).withMinute(1)),
            PhotoSpec("groom", burstId = 3, burstRank = 0, landscape = true, takenAt = DAY.withHour(10).withMinute(2)),
            PhotoSpec("couple", burstId = 4, burstRank = 0, landscape = false, takenAt = DAY.withHour(10).withMinute(3)),
            PhotoSpec("groom", burstId = 5, burstRank = 0, landscape = false, takenAt = DAY.withHour(15)),
            PhotoSpec("couple", burstId = 2, burstRank = 0, landscape = true, takenAt = DAY.withHour(15).withMinute(1)),
            PhotoSpec("couple", burstId = 2, burstRank = 1, landscape = true, takenAt = DAY.withHour(15).withMinute(2)),
            PhotoSpec("couple", burstId = 6, burstRank = 0, landscape = true, takenAt = DAY.withHour(18)),
            PhotoSpec("group", burstId = 7, burstRank = 0, landscape = false, takenAt = null),
        )
    }

    /** [AgentScene]의 표대로 만든다. */
    fun 웨딩_갤러리(maxSelectablePhotoCount: Int? = null, maxRetouchRoundCount: Int? = null): AgentScene {
        val gallery = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = maxSelectablePhotoCount, maxRetouchRoundCount = maxRetouchRoundCount)
        val galleryId = gallery.galleryId

        val outdoor = folderFixture.컨셉(galleryId, "야외", sortOrder = 0)
        val white = folderFixture.컨셉(galleryId, "화이트", sortOrder = 1)
        val whiteA = folderFixture.세부(galleryId, white, "화이트A")
        val garden = folderFixture.세부(galleryId, outdoor, "정원", sortOrder = 0)
        val sunset = folderFixture.세부(galleryId, outdoor, "노을", sortOrder = 1)

        val photos = photoFixture.업로드된_사진(galleryId, PHOTO_SPECS.size)
        for ((photoId, spec) in photos.zip(PHOTO_SPECS)) {
            photoFixture.피사체_적재(photoId, spec.subjects, spec.burstId, spec.burstRank)
            val (width, height) = if (spec.landscape) LANDSCAPE else PORTRAIT
            photoFixture.메타데이터_적재(photoId, width, height, spec.takenAt)
        }
        folderFixture.배정(galleryId, whiteA, photos.subList(0, 4))
        folderFixture.배정(galleryId, garden, photos.subList(4, 7))
        folderFixture.배정(galleryId, sunset, photos.subList(7, 8))

        return AgentScene(
            gallery = gallery,
            whiteConceptId = white,
            whiteDetailId = whiteA,
            outdoorConceptId = outdoor,
            gardenDetailId = garden,
            sunsetDetailId = sunset,
            photoIds = photos,
        )
    }

    /**
     * [AgentScene]의 반응 표대로 남긴다 — 별점 p[0]=5 · p[3]=4 · p[5]=2, 메모 p[1], 댓글 p[2](부부)·p[3](하객), 하객 좋아요 p[0]·p[3](화이트 공유 세션).
     * 별점·댓글·좋아요는 서비스로 남겨 실제 규칙(공유 세션 안의 사진만 좋아요 가능 등)을 타고, 메모만 표에 직접 둔다(개인 갤러리 전용 정책).
     */
    fun 반응_남기기(scene: AgentScene) {
        val memberId = scene.memberId
        photoRatingService.rate(scene.galleryId, scene.photoIds[0], memberId, RatePhotoRequest(score = 5))
        photoRatingService.rate(scene.galleryId, scene.photoIds[3], memberId, RatePhotoRequest(score = 4))
        photoRatingService.rate(scene.galleryId, scene.photoIds[5], memberId, RatePhotoRequest(score = 2))
        photoFixture.메모_적재(scene.photoIds[1], memberId, "엄마가 좋아할 듯")
        photoCommentService.write(scene.galleryId, scene.photoIds[2], memberId, WritePhotoCommentRequest(content = "이거 어때?"))

        val session = collabSessionService.open(
            scene.galleryId,
            memberId,
            OpenCollabSessionRequest(conceptFolderId = scene.whiteConceptId, name = "가족 의견"),
        )
        val collabToken = session.collabUrl.substringAfterLast('/')
        val guest = collabGuestService.enter(collabToken, EnterCollabRequest(nickname = "어머니"))
        collabGuestService.like(collabToken, scene.photoIds[0], null, guest.guestToken)
        collabGuestService.like(collabToken, scene.photoIds[3], null, guest.guestToken)
        collabGuestService.writeComment(collabToken, scene.photoIds[3], null, guest.guestToken, WriteCollabCommentRequest(content = "이 사진이 제일 예뻐요"))
    }

    private data class PhotoSpec(
        val subjects: String,
        val burstId: Int,
        val burstRank: Int,
        val landscape: Boolean,
        val takenAt: LocalDateTime?,
    )
}

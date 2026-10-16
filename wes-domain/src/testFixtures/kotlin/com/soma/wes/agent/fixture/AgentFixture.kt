package com.soma.wes.agent.fixture

import com.soma.wes.folder.fixture.FolderFixture
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.photo.fixture.PhotoFixture
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class AgentFixture(
    private val galleryFixture: GalleryFixture,
    private val folderFixture: FolderFixture,
    private val photoFixture: PhotoFixture,
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
    fun 웨딩_갤러리(maxSelectablePhotoCount: Int? = null): AgentScene {
        val gallery = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = maxSelectablePhotoCount)
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

    private data class PhotoSpec(
        val subjects: String,
        val burstId: Int,
        val burstRank: Int,
        val landscape: Boolean,
        val takenAt: LocalDateTime?,
    )
}

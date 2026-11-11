package com.soma.wes.album.service

import com.soma.wes.album.domain.AlbumShareMode
import com.soma.wes.album.domain.AlbumStatus
import com.soma.wes.album.dto.AlbumLayoutDto
import com.soma.wes.album.dto.request.ConfirmAlbumRequest
import com.soma.wes.album.dto.request.ReturnAlbumRequest
import com.soma.wes.album.dto.request.SaveAlbumRequest
import com.soma.wes.album.dto.request.SendAlbumRequest
import com.soma.wes.album.exception.AlbumErrorCode
import com.soma.wes.album.exception.AlbumException
import com.soma.wes.gallery.domain.GalleryStage
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.gallery.fixture.PersonalGalleryFixture
import com.soma.wes.gallery.service.GalleryService
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.service.UserNotificationService
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.retouch.fixture.RetouchFixture
import com.soma.wes.retouch.repository.RetouchPhotoRepository
import com.soma.wes.retouch.service.RetouchService
import com.soma.wes.selection.dto.request.DeselectPhotosRequest
import com.soma.wes.selection.fixture.SelectionFixture
import com.soma.wes.selection.service.PhotoSelectionService
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper

@IntegrationTest
class AlbumServiceTest @Autowired constructor(
    private val albums: AlbumService,
    private val galleryFixture: GalleryFixture,
    private val personalGalleryFixture: PersonalGalleryFixture,
    private val userFixture: UserFixture,
    private val photoFixture: PhotoFixture,
    private val selectionFixture: SelectionFixture,
    private val retouchFixture: RetouchFixture,
    private val retouchPhotos: RetouchPhotoRepository,
    private val retouch: RetouchService,
    private val galleries: GalleryService,
    private val selections: PhotoSelectionService,
    private val notifications: UserNotificationService,
    private val mapper: ObjectMapper,
) {

    @Nested
    @DisplayName("초안을 저장하고 열람할 때")
    inner class Draft {

        @Test
        fun `오래된 버전과 페이지 크기 변경을 거절하고 초안은 작가에게만 보인다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val layout = layoutOf(selectedPhotos(fixture, count = 1))
            val studioId = fixture.photographer.requiredId
            val clientId = fixture.member.requiredId

            // when
            val draft = albums.saveStudio(fixture.galleryId, studioId, SaveAlbumRequest(expectedRevision = 0, album = layout))
            val saved = albums.get(fixture.galleryId, studioId)
            val managerGallery = galleries.get(fixture.galleryId, studioId)
            val clientGallery = galleries.get(fixture.galleryId, clientId)

            // then
            assertSoftly { softly ->
                softly.assertThat(saved.album).isEqualTo(layout)
                softly.assertThat(saved.revision).isEqualTo(draft.revision)
                softly.assertThat(managerGallery.albumStatus).isEqualTo(AlbumStatus.DRAFT)
                softly.assertThat(clientGallery.hasAlbum).isFalse()
            }
            assertThatThrownBy {
                albums.saveStudio(fixture.galleryId, studioId, SaveAlbumRequest(expectedRevision = 0, album = layout))
            }.isInstanceOf(AlbumException::class.java)
                .extracting("errorCode").isEqualTo(AlbumErrorCode.REVISION_MISMATCH)
            assertThatThrownBy {
                val resized = layout.copy(page = layout.page.copy(w = 300.0))
                albums.saveStudio(fixture.galleryId, studioId, SaveAlbumRequest(expectedRevision = saved.revision, album = resized))
            }.isInstanceOf(AlbumException::class.java)
                .extracting("errorCode").isEqualTo(AlbumErrorCode.PAGE_SIZE_LOCKED)
            assertThatThrownBy { albums.get(fixture.galleryId, clientId) }
                .isInstanceOf(AlbumException::class.java)
                .extracting("errorCode").isEqualTo(AlbumErrorCode.NOT_FOUND)
        }

        @Test
        fun `선택하지 않은 사진과 선택 장수를 넘는 프레임은 저장할 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val selected = selectedPhotos(fixture, count = 1)
            val unselected = photoFixture.업로드된_사진(fixture.galleryId, count = 1).single()
            val studioId = fixture.photographer.requiredId

            // when & then
            assertThatThrownBy {
                albums.saveStudio(fixture.galleryId, studioId, SaveAlbumRequest(expectedRevision = 0, album = layoutOf(listOf(unselected))))
            }.isInstanceOf(AlbumException::class.java)
                .extracting("errorCode").isEqualTo(AlbumErrorCode.INVALID_ASSIGNMENT)
            assertThatThrownBy {
                val layout = layoutOf(selected + unselected).copy(assign = emptyMap())
                albums.saveStudio(fixture.galleryId, studioId, SaveAlbumRequest(expectedRevision = 0, album = layout))
            }.isInstanceOf(AlbumException::class.java)
                .extracting("errorCode").isEqualTo(AlbumErrorCode.TOO_MANY_FRAMES)
            assertThat(galleries.get(fixture.galleryId, studioId).hasAlbum).isFalse()
        }

        @Test
        fun `선택에서 빠진 사진은 프레임을 비우고 크롭을 지운다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val ids = selectedPhotos(fixture, count = 2)
            val studioId = fixture.photographer.requiredId
            val layout = layoutOf(ids).copy(crop = mapOf("fr1" to cropOf(ids.first())))
            albums.saveStudio(fixture.galleryId, studioId, SaveAlbumRequest(expectedRevision = 0, album = layout))

            // when
            val deselect = DeselectPhotosRequest(photoIds = listOf(ids.first()))
            selections.deselect(fixture.galleryId, fixture.member.requiredId, deselect)
            val updated = albums.get(fixture.galleryId, studioId).album

            // then
            assertSoftly { softly ->
                softly.assertThat(updated.spreads).isEqualTo(layout.spreads)
                softly.assertThat(updated.assign).isEqualTo(mapOf("fr2" to ids.last()))
                softly.assertThat(updated.crop).isEmpty()
                softly.assertThat(updated.gone).isEqualTo(mapOf("fr1" to ids.first()))
            }
        }

        @Test
        fun `개인 갤러리 소유자와 무관한 사용자는 앨범을 읽을 수 없다`() {
            // given
            val personal = personalGalleryFixture.파트너와_개인_갤러리()
            val studio = galleryFixture.멤버와_열린_갤러리()
            val stranger = userFixture.사용자()

            // when & then
            assertThatThrownBy { albums.get(personal.galleryId, personal.ownerId) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
            assertThatThrownBy { albums.get(studio.galleryId, stranger.requiredId) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
    }

    @Nested
    @DisplayName("앨범을 보내고 돌려받을 때")
    inner class Delivery {

        @Test
        fun `스튜디오 초안과 고객 사본을 분리하고 고객이 돌려보낸 변경을 확정한다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val studioId = fixture.photographer.requiredId
            val clientId = fixture.member.requiredId
            val ids = selectedPhotos(fixture, count = 2)
            val layout = layoutOf(ids)
            val draft = albums.saveStudio(fixture.galleryId, studioId, SaveAlbumRequest(expectedRevision = 0, album = layout))
            retouchFixture.완료된_회차(fixture.galleryId)
            retouch.confirm(fixture.galleryId, clientId)
            val albumStage = galleries.get(fixture.galleryId, studioId)

            // when
            val sent = albums.send(fixture.galleryId, studioId, SendAlbumRequest(expectedRevision = draft.revision, mode = AlbumShareMode.EDIT))
            val studioLayout = layout.copy(crop = mapOf("fr2" to cropOf(ids.last())))
            val changedDraft = albums.saveStudio(fixture.galleryId, studioId, SaveAlbumRequest(expectedRevision = sent.revision, album = studioLayout))
            val readByClient = albums.get(fixture.galleryId, clientId)
            val clientLayout = layout.copy(crop = mapOf("fr1" to cropOf(ids.first())))
            val saved = albums.saveClient(fixture.galleryId, clientId, SaveAlbumRequest(expectedRevision = changedDraft.revision, album = clientLayout))
            val returned = albums.returnToStudio(fixture.galleryId, clientId, ReturnAlbumRequest(saved.revision))
            val review = albums.get(fixture.galleryId, studioId)
            val confirmed = albums.confirm(fixture.galleryId, studioId, ConfirmAlbumRequest(returned.revision))
            val archived = galleries.get(fixture.galleryId, clientId)

            // then
            assertSoftly { softly ->
                softly.assertThat(albumStage.stage).isEqualTo(GalleryStage.ALBUM)
                softly.assertThat(albumStage.archivedUntil).isNull()
                softly.assertThat(readByClient.album).isEqualTo(layout)
                softly.assertThat(review.album).isEqualTo(studioLayout)
                softly.assertThat(review.returnedAlbum).isEqualTo(clientLayout)
                softly.assertThat(review.changedFrameIds).containsExactly("fr1")
                softly.assertThat(confirmed.status).isEqualTo(AlbumStatus.CONFIRMED)
                softly.assertThat(albums.get(fixture.galleryId, clientId).album).isEqualTo(clientLayout)
                softly.assertThat(archived.stage).isEqualTo(GalleryStage.ARCHIVED)
                softly.assertThat(archived.albumStatus).isEqualTo(AlbumStatus.CONFIRMED)
                softly.assertThat(notifications.list(studioId, null, fixture.galleryId).map { it.type })
                    .contains(UserNotificationType.ALBUM_RETURNED)
                softly.assertThat(notifications.list(clientId, null, fixture.galleryId).map { it.type })
                    .contains(UserNotificationType.ALBUM_SENT, UserNotificationType.ALBUM_CONFIRMED)
            }
        }

        @Test
        fun `재발송은 고객 사본을 교체하고 이전 버전과 레이아웃 변경을 거절한다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val studioId = fixture.photographer.requiredId
            val clientId = fixture.member.requiredId
            val ids = selectedPhotos(fixture, count = 1)
            val layout = layoutOf(ids)
            retouchFixture.완료된_회차(fixture.galleryId)
            retouch.confirm(fixture.galleryId, clientId)
            val draft = albums.saveStudio(fixture.galleryId, studioId, SaveAlbumRequest(expectedRevision = 0, album = layout))
            val first = albums.send(fixture.galleryId, studioId, SendAlbumRequest(expectedRevision = draft.revision, mode = AlbumShareMode.EDIT))
            val clientLayout = layout.copy(crop = mapOf("fr1" to cropOf(ids.single())))
            val edited = albums.saveClient(fixture.galleryId, clientId, SaveAlbumRequest(expectedRevision = first.revision, album = clientLayout))

            // when
            val resent = albums.send(fixture.galleryId, studioId, SendAlbumRequest(expectedRevision = edited.revision, mode = AlbumShareMode.EDIT))

            // then
            assertThat(albums.get(fixture.galleryId, clientId).album).isEqualTo(layout)
            assertThatThrownBy {
                albums.saveClient(fixture.galleryId, clientId, SaveAlbumRequest(expectedRevision = edited.revision, album = clientLayout))
            }.isInstanceOf(AlbumException::class.java)
                .extracting("errorCode").isEqualTo(AlbumErrorCode.REVISION_MISMATCH)
            assertThatThrownBy {
                val changed = layout.copy(spreads = listOf(layout.spreads.single().copy(id = "different")))
                albums.saveClient(fixture.galleryId, clientId, SaveAlbumRequest(expectedRevision = resent.revision, album = changed))
            }.isInstanceOf(AlbumException::class.java)
                .extracting("errorCode").isEqualTo(AlbumErrorCode.CLIENT_LAYOUT_LOCKED)
        }

        @Test
        fun `보정 뒤 첫 앨범을 만들고 모든 사진을 채우면 보기 전용 발송 즉시 확정된다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val studioId = fixture.photographer.requiredId
            val clientId = fixture.member.requiredId
            val layout = layoutOf(selectedPhotos(fixture, count = 1))
            retouchFixture.완료된_회차(fixture.galleryId)
            retouch.confirm(fixture.galleryId, clientId)
            val before = galleries.get(fixture.galleryId, studioId)
            val emptyLayout = layout.copy(assign = emptyMap())
            val draft = albums.saveStudio(fixture.galleryId, studioId, SaveAlbumRequest(expectedRevision = 0, album = emptyLayout))
            val incomplete = SendAlbumRequest(expectedRevision = draft.revision, mode = AlbumShareMode.VIEW)

            // when & then
            assertThatThrownBy { albums.send(fixture.galleryId, studioId, incomplete) }
                .isInstanceOf(AlbumException::class.java)
                .extracting("errorCode").isEqualTo(AlbumErrorCode.INCOMPLETE)

            val saved = albums.saveStudio(fixture.galleryId, studioId, SaveAlbumRequest(expectedRevision = draft.revision, album = layout))
            val viewRequest = mapper.readValue(
                """{"expectedRevision":${saved.revision},"mode":"view"}""",
                SendAlbumRequest::class.java,
            )
            val sent = albums.send(fixture.galleryId, studioId, viewRequest)
            val clientView = albums.get(fixture.galleryId, clientId)
            assertSoftly { softly ->
                softly.assertThat(before.stage).isEqualTo(GalleryStage.ARCHIVED)
                softly.assertThat(sent.status).isEqualTo(AlbumStatus.CONFIRMED)
                softly.assertThat(clientView.shareMode).isEqualTo(AlbumShareMode.VIEW)
                softly.assertThat(clientView.album).isEqualTo(layout)
            }
            assertThatThrownBy {
                albums.saveClient(fixture.galleryId, clientId, SaveAlbumRequest(expectedRevision = sent.revision, album = layout))
            }.isInstanceOf(AlbumException::class.java)
                .extracting("errorCode").isEqualTo(AlbumErrorCode.INVALID_STATE)
        }
    }

    @Nested
    @DisplayName("앨범에 넣을 보정본을 읽을 때")
    inner class Photos {

        @Test
        fun `사진마다 최신 완료 회차만 쓰고 이전 결과와 보정 전 사진을 함께 돌려준다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val ids = selectedPhotos(fixture, count = 3)
            retouchFixture.결과와_함께_완료된_회차(fixture.galleryId, roundNo = 1, photoIds = ids.take(2))
            retouchFixture.결과와_함께_완료된_회차(fixture.galleryId, roundNo = 2, photoIds = ids.take(1))
            val pending = retouchFixture.결과_없는_제출된_회차(fixture.galleryId, roundNo = 3, photoIds = ids.take(1)).single()
            pending.writeResult(resultKey = "galleries/${fixture.galleryId}/retouch/results/3/pending.jpg", resultContentType = "image/jpeg")
            retouchPhotos.saveAndFlush(pending)

            // when
            val photos = albums.listPhotos(fixture.galleryId, fixture.photographer.requiredId).photos

            // then
            assertSoftly { softly ->
                softly.assertThat(photos.map { it.photoId }).containsExactlyElementsOf(ids)
                softly.assertThat(photos[0].resultUrl).contains("/results/2/")
                softly.assertThat(photos[1].resultUrl).contains("/results/1/")
                softly.assertThat(photos[2].resultUrl).isNull()
            }
        }
    }

    private fun selectedPhotos(fixture: OpenGallery, count: Int): List<Long> {
        val ids = photoFixture.업로드된_사진(fixture.galleryId, count = count)
        selectionFixture.담긴_사진(selectionFixture.셀렉(fixture.galleryId), ids)
        return ids
    }

    private fun layoutOf(ids: List<Long>): AlbumLayoutDto {
        val assignments = ids.mapIndexed { index, id -> "fr${index + 1}" to id }.toMap()
        val frames = assignments.keys.map { AlbumLayoutDto.Frame(id = it, p = 0, x = 0.08, y = 0.08, w = 0.84, h = 0.84) }
        return AlbumLayoutDto(
            page = AlbumLayoutDto.Page(w = 305.0, h = 305.0, name = "12 × 12"),
            spreads = listOf(AlbumLayoutDto.Spread(id = "sp1", frames = frames)),
            assign = assignments,
        )
    }

    private fun cropOf(photoId: Long): AlbumLayoutDto.Crop = AlbumLayoutDto.Crop(pid = photoId, s = 1.4, px = 0.3, py = 0.5)
}

package com.soma.wes.collab.service

import com.soma.wes.category.dto.request.MoveCategoryPhotosRequest
import com.soma.wes.category.service.CategoryService
import com.soma.wes.collab.domain.CollabSelectionMode
import com.soma.wes.collab.dto.request.CollabPhotoIdsRequest
import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.fixture.CollabFixture
import com.soma.wes.collab.repository.CollabSessionPhotoRepository
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.PersonalGalleryFixture
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.support.IntegrationTest
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

@IntegrationTest
class CollabManualFolderServiceTest @Autowired constructor(
    private val service: CollabSessionService,
    private val query: CollabSessionQueryService,
    private val guest: CollabGuestService,
    private val guestQuery: CollabGuestQueryService,
    private val fixtures: CollabFixture,
    private val galleryFixture: GalleryFixture,
    private val personalFixture: PersonalGalleryFixture,
    private val photoFixture: PhotoFixture,
    private val categories: CategoryService,
    private val sessions: CollabSessionRepository,
    private val memberships: CollabSessionPhotoRepository,
    private val photos: PhotoRepository,
    private val galleries: GalleryRepository,
    private val jdbc: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) {
    private fun assertCode(code: CollabErrorCode, call: () -> Any) {
        assertThatThrownBy { call() }.isInstanceOf(CollabException::class.java).extracting("errorCode").isEqualTo(code)
    }

    @Nested
    @DisplayName("직접 담는 공유폴더")
    inner class Manual {
        @Test
        fun `이름만으로 여러 빈 폴더를 만들고 개인 파트너도 관리한다`() {
            // given
            val fixture = personalFixture.파트너와_개인_갤러리()
            // when
            val first = service.open(fixture.galleryId, fixture.partnerId, OpenCollabSessionRequest(name = " 가족 의견 "))
            val second = service.open(fixture.galleryId, fixture.ownerId, OpenCollabSessionRequest(name = "가족 의견"))
            // then
            assertThat(first.name).isEqualTo("가족 의견")
            assertThat(first.selectionMode).isEqualTo(CollabSelectionMode.MANUAL)
            assertThat(first.conceptFolderId).isNull()
            assertThat(first.photoCount).isZero()
            assertThat(first.includeAllAlbums).isFalse()
            assertThat(second.sessionId).isNotEqualTo(first.sessionId)
            assertThat(query.list(fixture.galleryId, fixture.partnerId)).hasSize(2)
            val landing = guestQuery.getLanding(first.collabUrl.substringAfterLast('/'))
            assertThat(landing.albums.map { it.sessionId }).containsExactly(first.sessionId)
            assertThat(landing.albums.single().conceptFolderId).isNull()
        }

        @Test
        fun `사진은 분류와 무관하게 여러 폴더에 담고 중복 추가는 한 번만 저장한다`() {
            // given
            val shared = fixtures.사진이_있는_세션()
            val userId = shared.gallery.member.requiredId
            val anotherPhoto = photoFixture.업로드된_사진(shared.galleryId, 1).single()
            val manual = service.open(shared.galleryId, userId, OpenCollabSessionRequest(name = "직접 담기", photoIds = listOf(shared.photoId)))
            // when
            val added = service.addPhotos(shared.galleryId, manual.sessionId, userId,
                CollabPhotoIdsRequest(listOf(shared.photoId, anotherPhoto, anotherPhoto)))
            service.addPhotos(shared.galleryId, manual.sessionId, userId, CollabPhotoIdsRequest(listOf(anotherPhoto)))
            categories.movePhotos(shared.galleryId, shared.gallery.photographer.requiredId,
                MoveCategoryPhotosRequest(photoIds = listOf(shared.photoId), targetDetailFolderId = null))
            // then
            assertThat(added.photoCount).isEqualTo(2L)
            assertThat(memberships.count()).isEqualTo(2L)
            assertThat(query.listPhotos(shared.galleryId, manual.sessionId, userId, 0, 20).contents.map { it.photoId })
                .containsExactlyInAnyOrder(shared.photoId, anotherPhoto)
            assertThat(query.get(shared.galleryId, shared.session.sessionId, userId).photoCount).isZero()
        }

        @Test
        fun `남의 사진과 업로드 미완료 사진이 섞이면 생성과 추가 제거를 모두 취소한다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val userId = fixture.member.requiredId
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, 1).single()
            val pending = photoFixture.대기중_사진(fixture.galleryId, 1).single()
            val other = fixtures.사진이_있는_세션()
            val before = sessions.count()
            // when & then
            assertCode(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND) {
                service.open(fixture.galleryId, userId, OpenCollabSessionRequest(name = "잘못된 생성", photoIds = listOf(photoId, other.photoId)))
            }
            assertThat(sessions.count()).isEqualTo(before)
            val manual = service.open(fixture.galleryId, userId, OpenCollabSessionRequest(name = "검증", photoIds = listOf(photoId)))
            for (invalid in listOf(other.photoId, pending)) {
                assertCode(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND) {
                    service.addPhotos(fixture.galleryId, manual.sessionId, userId, CollabPhotoIdsRequest(listOf(photoId, invalid)))
                }
                assertCode(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND) {
                    service.removePhotos(fixture.galleryId, manual.sessionId, userId, CollabPhotoIdsRequest(listOf(photoId, invalid)))
                }
            }
            assertThat(query.get(fixture.galleryId, manual.sessionId, userId).photoCount).isEqualTo(1L)
            for (ids in listOf(emptyList(), listOf(-1L), List(201) { photoId })) {
                assertCode(CollabErrorCode.INVALID_PHOTO_IDS) {
                    service.addPhotos(fixture.galleryId, manual.sessionId, userId, CollabPhotoIdsRequest(ids))
                }
            }
        }

        @Test
        fun `제거는 해당 세션 반응만 지우고 게스트가 빠진 사진에 접근할 수 없다`() {
            // given
            val shared = fixtures.사진이_있는_세션()
            val userId = shared.gallery.member.requiredId
            val manual = service.open(shared.galleryId, userId, OpenCollabSessionRequest(name = "직접", photoIds = listOf(shared.photoId)))
            val token = manual.collabUrl.substringAfterLast('/')
            val viewer = guest.enter(token, EnterCollabRequest("가족"))
            guest.like(token, shared.photoId, null, viewer.guestToken)
            guest.writeComment(token, shared.photoId, null, viewer.guestToken, WriteCollabCommentRequest("수동 의견"))
            val legacyViewer = guest.enter(shared.token, EnterCollabRequest("기존"))
            guest.like(shared.token, shared.photoId, null, legacyViewer.guestToken)
            guest.writeComment(shared.token, shared.photoId, null, legacyViewer.guestToken, WriteCollabCommentRequest("기존 의견"))
            // when
            val removed = service.removePhotos(shared.galleryId, manual.sessionId, userId, CollabPhotoIdsRequest(listOf(shared.photoId)))
            // then
            assertThat(removed.photoCount).isZero()
            assertThat(photos.findById(shared.photoId)).isPresent
            assertThat(guestQuery.listPhotos(shared.token, null, legacyViewer.guestToken, 0, 20).contents.single().likeCount).isEqualTo(1L)
            assertThat(guestQuery.listComments(shared.token, shared.photoId, null, null, 0, 20).contents).hasSize(1)
            assertThat(query.listViewerComments(shared.galleryId, userId).map { it.content }).containsExactly("기존 의견")
            assertCode(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND) { guest.like(token, shared.photoId, null, viewer.guestToken) }
            assertCode(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND) {
                guest.writeComment(token, shared.photoId, null, viewer.guestToken, WriteCollabCommentRequest("불가"))
            }
            assertCode(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND) { guestQuery.listComments(token, shared.photoId, null, null, 0, 20) }
            service.addPhotos(shared.galleryId, manual.sessionId, userId, CollabPhotoIdsRequest(listOf(shared.photoId)))
            assertThat(guestQuery.listPhotos(token, null, viewer.guestToken, 0, 20).contents.single().likeCount).isZero()
            assertThat(guestQuery.listComments(token, shared.photoId, null, null, 0, 20).contents).isEmpty()
        }

        @Test
        fun `휴지통 사진은 개수 목록 반응에서 숨기고 복원하면 같은 구성으로 돌아온다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, 1).single()
            val session = service.open(fixture.galleryId, fixture.member.requiredId,
                OpenCollabSessionRequest(name = "직접", photoIds = listOf(photoId)))
            val token = session.collabUrl.substringAfterLast('/')
            // when
            jdbc.update("UPDATE photos SET deleted_at = now() WHERE id = ?", photoId)
            // then
            assertThat(guestQuery.getLanding(token).photoCount).isZero()
            assertThat(guestQuery.listPhotos(token, null, null, 0, 20).contents).isEmpty()
            assertThat(memberships.count()).isEqualTo(1L)
            jdbc.update("UPDATE photos SET deleted_at = NULL WHERE id = ?", photoId)
            assertThat(guestQuery.getLanding(token).photoCount).isEqualTo(1L)
        }
    }

    @Nested
    @DisplayName("기존 동적 공유 호환")
    inner class Compatibility {
        @Test
        fun `현재 사진을 수동으로 전환하면 링크 반응 참여자를 보존하고 이후 분류는 따르지 않는다`() {
            // given
            val shared = fixtures.사진이_있는_세션()
            val userId = shared.gallery.member.requiredId
            val viewer = guest.enter(shared.token, EnterCollabRequest("가족"))
            guest.like(shared.token, shared.photoId, null, viewer.guestToken)
            guest.writeComment(shared.token, shared.photoId, null, viewer.guestToken, WriteCollabCommentRequest("좋아요"))
            val extra = photoFixture.업로드된_사진(shared.galleryId, 1).single()
            assertCode(CollabErrorCode.MANUAL_CONVERSION_REQUIRED) {
                service.addPhotos(shared.galleryId, shared.session.sessionId, userId, CollabPhotoIdsRequest(listOf(extra)))
            }
            assertCode(CollabErrorCode.MANUAL_CONVERSION_REQUIRED) {
                service.removePhotos(shared.galleryId, shared.session.sessionId, userId, CollabPhotoIdsRequest(listOf(shared.photoId)))
            }
            // when
            val converted = service.convertToManual(shared.galleryId, shared.session.sessionId, userId)
            service.convertToManual(shared.galleryId, shared.session.sessionId, userId)
            categories.movePhotos(shared.galleryId, shared.gallery.photographer.requiredId,
                MoveCategoryPhotosRequest(photoIds = listOf(extra), targetDetailFolderId = shared.detailId))
            categories.movePhotos(shared.galleryId, shared.gallery.photographer.requiredId,
                MoveCategoryPhotosRequest(photoIds = listOf(shared.photoId), targetDetailFolderId = null))
            // then
            assertThat(converted.selectionMode).isEqualTo(CollabSelectionMode.MANUAL)
            assertThat(converted.conceptFolderId).isNull()
            assertThat(converted.collabUrl).isEqualTo(shared.session.collabUrl)
            assertThat(converted.expiresAt).isEqualTo(shared.session.expiresAt)
            val contents = guestQuery.listPhotos(shared.token, null, viewer.guestToken, 0, 20).contents
            assertThat(contents.map { it.photoId }).containsExactly(shared.photoId)
            assertThat(contents.single().liked).isTrue()
            assertThat(guestQuery.listComments(shared.token, shared.photoId, null, viewer.guestToken, 0, 20).contents.single().mine).isTrue()
        }

        @Test
        fun `분류 연결과 사진 목록을 함께 보내면 기존 링크도 변경하지 않는다`() {
            // given
            val shared = fixtures.사진이_있는_세션()
            // when & then
            assertCode(CollabErrorCode.INVALID_SELECTION_SOURCE) {
                service.open(shared.galleryId, shared.gallery.member.requiredId,
                    OpenCollabSessionRequest(conceptFolderId = shared.session.conceptFolderId, name = "변경", photoIds = listOf(shared.photoId)))
            }
            assertThat(query.get(shared.galleryId, shared.session.sessionId, shared.gallery.member.requiredId).name).isEqualTo(shared.session.name)
        }
    }

    @Nested
    @DisplayName("스코프와 동시성")
    inner class Boundaries {
        @Test
        fun `작가는 수동 폴더를 관리할 수 없고 보관된 갤러리는 직접 변경을 거절한다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, 1).single()
            val session = service.open(fixture.galleryId, fixture.member.requiredId, OpenCollabSessionRequest(name = "직접"))
            // when & then
            assertThatThrownBy { service.addPhotos(fixture.galleryId, session.sessionId, fixture.photographer.requiredId, CollabPhotoIdsRequest(listOf(photoId))) }
                .isInstanceOf(GalleryException::class.java).extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
            val gallery = galleries.findById(fixture.galleryId).orElseThrow().apply { close() }
            galleries.saveAndFlush(gallery)
            for (call in listOf<() -> Any>(
                { service.addPhotos(fixture.galleryId, session.sessionId, fixture.member.requiredId, CollabPhotoIdsRequest(listOf(photoId))) },
                { service.removePhotos(fixture.galleryId, session.sessionId, fixture.member.requiredId, CollabPhotoIdsRequest(listOf(photoId))) },
                { service.convertToManual(fixture.galleryId, session.sessionId, fixture.member.requiredId) },
            )) {
                assertThatThrownBy { call() }.isInstanceOf(GalleryException::class.java)
                    .extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ARCHIVED)
            }
        }

        @Test
        fun `같은 사진을 동시에 추가해도 한 행만 남는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, 1).single()
            val session = service.open(fixture.galleryId, fixture.member.requiredId, OpenCollabSessionRequest(name = "직접"))
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures = (1..2).map { executor.submit {
                    check(start.await(10, TimeUnit.SECONDS))
                    service.addPhotos(fixture.galleryId, session.sessionId, fixture.member.requiredId, CollabPhotoIdsRequest(listOf(photoId)))
                } }
                // when
                start.countDown()
                futures.forEach { it.get(10, TimeUnit.SECONDS) }
            } finally { executor.shutdownNow() }
            // then
            assertThat(memberships.count()).isEqualTo(1L)
            assertThat(query.get(fixture.galleryId, session.sessionId, fixture.member.requiredId).photoCount).isEqualTo(1L)
        }

        @Test
        fun `사진 정리 중인 트랜잭션과 공유 변경이 서로를 기다리지 않는다`() {
            // given
            val linked = fixtures.사진이_있는_세션()
            val userId = linked.gallery.member.requiredId
            val manual = service.open(linked.galleryId, userId, OpenCollabSessionRequest(name = "직접"))
            val token = manual.collabUrl.substringAfterLast('/')
            val visitor = guest.enter(token, EnterCollabRequest("하객"))
            val executor = Executors.newSingleThreadExecutor()

            fun whilePhotoCleanup(sessionId: Long, operation: () -> Unit) {
                var future: Future<*>? = null
                transactionTemplate.executeWithoutResult {
                    val blocker = checkNotNull(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java))
                    checkNotNull(photos.findWithLockByIdAndGalleryId(linked.photoId, linked.galleryId))
                    future = executor.submit { operation() }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    var waiting = false
                    while (!waiting && System.nanoTime() < deadline && !checkNotNull(future).isDone) {
                        waiting = jdbc.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))",
                            Boolean::class.java, blocker,
                        ) == true
                        if (!waiting) Thread.sleep(20)
                    }
                    assertThat(waiting).describedAs("공유 변경이 사진 정리 완료를 기다린다").isTrue()
                    // 휴지통은 사진 뒤에 세션을 잠근다. 공유 변경이 세션부터 잡았다면 여기서 실패한다.
                    assertThat(jdbc.queryForObject(
                        "SELECT id FROM collab_sessions WHERE id = ? FOR UPDATE NOWAIT", Long::class.java, sessionId,
                    )).isEqualTo(sessionId)
                }
                checkNotNull(future).get(10, TimeUnit.SECONDS)
            }

            try {
                // when
                whilePhotoCleanup(manual.sessionId) {
                    service.addPhotos(linked.galleryId, manual.sessionId, userId, CollabPhotoIdsRequest(listOf(linked.photoId)))
                }
                whilePhotoCleanup(manual.sessionId) {
                    guest.writeComment(token, linked.photoId, visitor.guestToken, WriteCollabCommentRequest("좋아요"))
                }
                whilePhotoCleanup(linked.session.sessionId) {
                    service.convertToManual(linked.galleryId, linked.session.sessionId, userId)
                }
            } finally { executor.shutdownNow() }
            // then
            assertThat(query.get(linked.galleryId, linked.session.sessionId, userId).selectionMode).isEqualTo(CollabSelectionMode.MANUAL)
            assertThat(guestQuery.listComments(token, linked.photoId, visitor.guestToken, 0, 20).contents).hasSize(1)
        }

        @Test
        fun `DB도 다른 갤러리 사진의 연결을 차단한다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val other = fixtures.사진이_있는_세션()
            val session = service.open(fixture.galleryId, fixture.member.requiredId, OpenCollabSessionRequest(name = "직접"))
            // when & then
            assertThatThrownBy {
                jdbc.update("INSERT INTO collab_session_photos(collab_session_id, gallery_id, photo_id) VALUES (?, ?, ?)",
                    session.sessionId, fixture.galleryId, other.photoId)
            }.isInstanceOf(DataIntegrityViolationException::class.java)
            assertThat(memberships.count()).isZero()
        }
    }
}

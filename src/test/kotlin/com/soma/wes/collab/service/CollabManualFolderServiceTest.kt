package com.soma.wes.collab.service

import com.soma.wes.folder.dto.request.CreateConceptFolderRequest
import com.soma.wes.folder.dto.request.CreateDetailFolderRequest
import com.soma.wes.folder.dto.request.MergeDetailFolderRequest
import com.soma.wes.folder.dto.request.MoveFolderPhotosRequest
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.service.FolderService
import com.soma.wes.collab.dto.request.CollabPhotoIdsRequest
import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest.PhotoScope.Type as PhotoScopeType
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.dto.response.CollabSessionResponse
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
import org.springframework.core.io.ClassPathResource
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
    private val categories: FolderService,
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
            assertThat(first.photoCount).isZero()
            assertThat(first.includeAllAlbums).isFalse()
            assertThat(second.sessionId).isNotEqualTo(first.sessionId)
            assertThat(query.list(fixture.galleryId, fixture.partnerId)).hasSize(2)
            val landing = guestQuery.getLanding(first.collabUrl.substringAfterLast('/'))
            assertThat(landing.albums.map { it.sessionId }).containsExactly(first.sessionId)
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
                MoveFolderPhotosRequest(photoIds = listOf(shared.photoId), targetDetailFolderId = null))
            // then
            assertThat(added.photoCount).isEqualTo(2L)
            assertThat(memberships.findAllByCollabSessionIdAndPhotoIdIn(manual.sessionId, listOf(shared.photoId, anotherPhoto))).hasSize(2)
            assertThat(query.listPhotos(shared.galleryId, manual.sessionId, userId, 0, 20).contents.map { it.photoId })
                .containsExactlyInAnyOrder(shared.photoId, anotherPhoto)
            // 컨셉으로 만든 공유폴더도 미분류로 빠진 사진을 그대로 담고 있다.
            assertThat(query.get(shared.galleryId, shared.session.sessionId, userId).photoCount).isEqualTo(1L)
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
            for (ids in listOf(emptyList(), listOf(-1L), List(CollabPhotoIdsRequest.MAX_BATCH_SIZE + 1) { photoId })) {
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
    @DisplayName("폴더와 따로 사는 공유폴더")
    inner class Independent {
        @Test
        fun `컨셉으로 만든 공유폴더는 사진을 옮기고 폴더를 합치고 지워도 사진과 반응이 그대로다`() {
            // given
            val shared = fixtures.사진이_있는_세션()
            val userId = shared.gallery.member.requiredId
            val photographer = shared.gallery.photographer.requiredId
            val viewer = guest.enter(shared.token, EnterCollabRequest("가족"))
            guest.like(shared.token, shared.photoId, null, viewer.guestToken)
            guest.writeComment(shared.token, shared.photoId, null, viewer.guestToken, WriteCollabCommentRequest("좋아요"))
            val later = photoFixture.업로드된_사진(shared.galleryId, 1).single()
            val otherConcept = categories.createConcept(shared.galleryId, photographer, CreateConceptFolderRequest("야외")).id
            val otherDetail = categories.createDetail(shared.galleryId, otherConcept, photographer, CreateDetailFolderRequest("숲")).id

            // when
            // 나중에 원본 컨셉에 들어온 사진은 따라 들어오지 않고, 원본 사진이 다른 컨셉으로 나가도 빠지지 않는다.
            categories.movePhotos(shared.galleryId, photographer,
                MoveFolderPhotosRequest(photoIds = listOf(later), targetDetailFolderId = shared.detailId))
            categories.mergeDetail(shared.galleryId, shared.conceptId, shared.detailId, photographer,
                MergeDetailFolderRequest(targetDetailFolderId = otherDetail))
            categories.movePhotos(shared.galleryId, photographer,
                MoveFolderPhotosRequest(photoIds = listOf(shared.photoId), targetDetailFolderId = null))
            categories.deleteConcept(shared.galleryId, shared.conceptId, photographer)
            categories.deleteConcept(shared.galleryId, otherConcept, photographer)

            // then
            val contents = guestQuery.listPhotos(shared.token, null, viewer.guestToken, 0, 20).contents
            assertThat(contents.map { it.photoId }).containsExactly(shared.photoId)
            assertThat(contents.single().liked).isTrue()
            assertThat(guestQuery.listComments(shared.token, shared.photoId, null, viewer.guestToken, 0, 20).contents.single().mine).isTrue()
            // 따로 사는 폴더라 직접 담고 뺄 수 있다.
            assertThat(service.addPhotos(shared.galleryId, shared.session.sessionId, userId, CollabPhotoIdsRequest(listOf(later))).photoCount)
                .isEqualTo(2L)
        }

        @Test
        fun `같은 컨셉으로 다시 만들면 새 공유폴더가 생긴다`() {
            // given
            val shared = fixtures.사진이_있는_세션()
            val userId = shared.gallery.member.requiredId

            // when
            val again = service.open(shared.galleryId, userId, OpenCollabSessionRequest(conceptFolderId = shared.conceptId, name = "다시"))

            // then
            assertThat(again.sessionId).isNotEqualTo(shared.session.sessionId)
            assertThat(again.collabUrl).isNotEqualTo(shared.session.collabUrl)
            assertThat(again.photoCount).isEqualTo(1L)
            assertThat(query.get(shared.galleryId, shared.session.sessionId, userId).name).isEqualTo(shared.session.name)
        }

        @Test
        fun `컨셉 연결과 사진 목록을 함께 보내면 공유폴더를 만들지 않는다`() {
            // given
            val shared = fixtures.사진이_있는_세션()
            val before = sessions.count()
            // when & then
            assertCode(CollabErrorCode.INVALID_SELECTION_SOURCE) {
                service.open(shared.galleryId, shared.gallery.member.requiredId,
                    OpenCollabSessionRequest(conceptFolderId = shared.conceptId, name = "변경", photoIds = listOf(shared.photoId)))
            }
            assertThat(sessions.count()).isEqualTo(before)
        }

        @Test
        fun `V36은 컨셉을 따라가던 공유폴더를 지금 보이는 사진으로 고정하고 연결을 끊는다`() {
            // given
            val shared = fixtures.사진이_있는_세션()
            val userId = shared.gallery.member.requiredId
            val photographer = shared.gallery.photographer.requiredId
            val trashedDetailPhoto = photoFixture.업로드된_사진(shared.galleryId, 1).single()
            val trashedPhoto = photoFixture.업로드된_사진(shared.galleryId, 1).single()
            val trashedDetail = categories.createDetail(shared.galleryId, shared.conceptId, photographer, CreateDetailFolderRequest("숨김")).id
            categories.movePhotos(shared.galleryId, photographer,
                MoveFolderPhotosRequest(photoIds = listOf(trashedDetailPhoto), targetDetailFolderId = trashedDetail))
            categories.movePhotos(shared.galleryId, photographer,
                MoveFolderPhotosRequest(photoIds = listOf(trashedPhoto), targetDetailFolderId = shared.detailId))
            jdbc.update("UPDATE detail_folders SET deleted_at = now() WHERE id = ?", trashedDetail)
            photos.saveAndFlush(photos.findById(trashedPhoto).orElseThrow().also { it.moveToTrash(java.time.ZonedDateTime.now()) })
            // 옛 컨셉 연결 공유폴더를 그대로 만든다 — V37이 지운 컬럼을 잠시 되살려 사진 목록 없이 컨셉만 가리키게 한다.
            jdbc.execute("ALTER TABLE collab_sessions ADD COLUMN concept_folder_id bigint")
            try {
                jdbc.update("DELETE FROM collab_session_photos WHERE collab_session_id = ?", shared.session.sessionId)
                jdbc.update("UPDATE collab_sessions SET concept_folder_id = ? WHERE id = ?", shared.conceptId, shared.session.sessionId)
                val versionBefore = checkNotNull(
                    jdbc.queryForObject("SELECT version FROM collab_sessions WHERE id = ?", Long::class.java, shared.session.sessionId),
                )

                // when
                ClassPathResource("db/migration/V36__freeze_concept_linked_shared_folders.sql").inputStream.use { sql ->
                    jdbc.execute(String(sql.readAllBytes()))
                }

                // then
                assertThat(jdbc.queryForObject(
                    "SELECT concept_folder_id FROM collab_sessions WHERE id = ?", Long::class.java, shared.session.sessionId,
                )).isNull()
                assertThat(jdbc.queryForObject("SELECT version FROM collab_sessions WHERE id = ?", Long::class.java, shared.session.sessionId))
                    .isEqualTo(versionBefore + 1)
            } finally {
                jdbc.execute("ALTER TABLE collab_sessions DROP COLUMN concept_folder_id")
            }
            // 휴지통 사진은 담아 두고 숨긴다(복원하면 보인다). 휴지통 세부 폴더의 사진은 지금 보이지 않으므로 담지 않는다.
            assertThat(jdbc.queryForList(
                "SELECT photo_id FROM collab_session_photos WHERE collab_session_id = ?", Long::class.java, shared.session.sessionId,
            )).containsExactlyInAnyOrder(shared.photoId, trashedPhoto)
            assertThat(query.listPhotos(shared.galleryId, shared.session.sessionId, userId, 0, 20).contents.map { it.photoId })
                .containsExactly(shared.photoId)
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
            } finally { executor.shutdownNow() }
            // then
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
            assertThat(query.get(fixture.galleryId, session.sessionId, fixture.member.requiredId).photoCount).isZero()
        }
    }

    @Nested
    @DisplayName("범위로 만드는 공유폴더")
    inner class Scoped {
        private fun scope(
            type: PhotoScopeType,
            conceptFolderIds: List<Long> = emptyList(),
            detailFolderIds: List<Long> = emptyList(),
            sessionIds: List<Long> = emptyList(),
        ) = OpenCollabSessionRequest.PhotoScope(type, conceptFolderIds, detailFolderIds, sessionIds)

        private fun galleryPhotoIds(galleryId: Long): List<Long> =
            jdbc.queryForList("SELECT id FROM photos WHERE gallery_id = ? ORDER BY id", Long::class.java, galleryId).filterNotNull()

        private fun timed(label: String, call: () -> CollabSessionResponse): CollabSessionResponse {
            val start = System.nanoTime()
            return call().also { println("[collab-scope] $label: ${(System.nanoTime() - start) / 1_000_000}ms") }
        }

        @Test
        fun `모든 사진 범위는 갤러리 최대 사진 수를 요청 한 번에 담고 휴지통과 업로드 미완료는 뺀다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            photoFixture.대량_업로드된_사진(fixture.galleryId, CollabPhotoIdsRequest.MAX_BATCH_SIZE)
            photoFixture.대기중_사진(fixture.galleryId, 1)
            val trashed = photoFixture.업로드된_사진(fixture.galleryId, 1).single()
            photos.saveAndFlush(photos.findById(trashed).orElseThrow().also { it.moveToTrash(java.time.ZonedDateTime.now()) })

            // when
            val session = timed("ALL ${CollabPhotoIdsRequest.MAX_BATCH_SIZE}") {
                service.open(fixture.galleryId, fixture.member.requiredId,
                    OpenCollabSessionRequest(name = "모든 사진", scope = scope(PhotoScopeType.ALL)))
            }

            // then
            assertThat(session.photoCount).isEqualTo(CollabPhotoIdsRequest.MAX_BATCH_SIZE.toLong())
            assertThat(memberships.count()).isEqualTo(CollabPhotoIdsRequest.MAX_BATCH_SIZE.toLong())
        }

        @Test
        fun `사진 id도 갤러리 최대 사진 수까지 요청 한 번에 담는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            photoFixture.대량_업로드된_사진(fixture.galleryId, CollabPhotoIdsRequest.MAX_BATCH_SIZE)
            val ids = galleryPhotoIds(fixture.galleryId)
            val userId = fixture.member.requiredId

            // when
            val session = timed("photoIds ${ids.size}") {
                service.open(fixture.galleryId, userId, OpenCollabSessionRequest(name = "직접", photoIds = ids))
            }
            val again = timed("addPhotos ${ids.size} (이미 담김)") {
                service.addPhotos(fixture.galleryId, session.sessionId, userId, CollabPhotoIdsRequest(ids))
            }

            // then
            assertThat(session.photoCount).isEqualTo(ids.size.toLong())
            assertThat(again.photoCount).isEqualTo(ids.size.toLong())
            assertThat(memberships.count()).isEqualTo(ids.size.toLong())
        }

        @Test
        fun `컨셉 폴더 범위는 여러 컨셉의 살아 있는 세부 폴더 사진만 담는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val (kept, inTrashedDetail, inOtherConcept, unassigned) = photoFixture.업로드된_사진(fixture.galleryId, 4)
            val photographer = fixture.photographer.requiredId
            fun detailWith(conceptId: Long, photoId: Long): Long {
                val detail = categories.createDetail(fixture.galleryId, conceptId, photographer, CreateDetailFolderRequest("세부"))
                categories.movePhotos(fixture.galleryId, photographer,
                    MoveFolderPhotosRequest(photoIds = listOf(photoId), targetDetailFolderId = detail.id))
                return detail.id
            }
            val first = categories.createConcept(fixture.galleryId, photographer, CreateConceptFolderRequest("본식")).id
            val second = categories.createConcept(fixture.galleryId, photographer, CreateConceptFolderRequest("야외")).id
            detailWith(first, kept)
            val trashedDetail = detailWith(first, inTrashedDetail)
            jdbc.update("UPDATE detail_folders SET deleted_at = now() WHERE id = ?", trashedDetail)
            detailWith(second, inOtherConcept)
            val other = fixtures.사진이_있는_세션()

            // when
            val session = service.open(fixture.galleryId, fixture.member.requiredId,
                OpenCollabSessionRequest(name = "컨셉", scope = scope(PhotoScopeType.CONCEPT_FOLDERS, listOf(first, second, first))))

            // then
            assertThat(query.listPhotos(fixture.galleryId, session.sessionId, fixture.member.requiredId, 0, 20).contents.map { it.photoId })
                .containsExactlyInAnyOrder(kept, inOtherConcept)
                .doesNotContain(inTrashedDetail, unassigned)
            assertThatThrownBy {
                service.open(fixture.galleryId, fixture.member.requiredId, OpenCollabSessionRequest(name = "남의 컨셉",
                    scope = scope(PhotoScopeType.CONCEPT_FOLDERS, listOf(first, other.conceptId))))
            }.isInstanceOf(FolderException::class.java).extracting("errorCode").isEqualTo(FolderErrorCode.CONCEPT_NOT_FOUND)
        }

        @Test
        fun `세부 폴더 범위는 고른 세부 폴더의 사진만 담는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val (first, skipped, third) = photoFixture.업로드된_사진(fixture.galleryId, 3)
            val photographer = fixture.photographer.requiredId
            val concept = categories.createConcept(fixture.galleryId, photographer, CreateConceptFolderRequest("본식")).id
            val details = listOf(first, skipped, third).map { photoId ->
                categories.createDetail(fixture.galleryId, concept, photographer, CreateDetailFolderRequest("세부")).id.also { detail ->
                    categories.movePhotos(fixture.galleryId, photographer,
                        MoveFolderPhotosRequest(photoIds = listOf(photoId), targetDetailFolderId = detail))
                }
            }
            val other = fixtures.사진이_있는_세션()

            // when
            val session = service.open(fixture.galleryId, fixture.member.requiredId, OpenCollabSessionRequest(name = "세부",
                scope = scope(PhotoScopeType.DETAIL_FOLDERS, detailFolderIds = listOf(details[0], details[2]))))

            // then
            assertThat(query.listPhotos(fixture.galleryId, session.sessionId, fixture.member.requiredId, 0, 20).contents.map { it.photoId })
                .containsExactlyInAnyOrder(first, third)
                .doesNotContain(skipped)
            assertThatThrownBy {
                service.open(fixture.galleryId, fixture.member.requiredId, OpenCollabSessionRequest(name = "남의 세부",
                    scope = scope(PhotoScopeType.DETAIL_FOLDERS, detailFolderIds = listOf(details[0], other.detailId))))
            }.isInstanceOf(FolderException::class.java).extracting("errorCode").isEqualTo(FolderErrorCode.DETAIL_NOT_FOUND)
        }

        @Test
        fun `공유폴더 범위는 수동 폴더와 컨셉 연결 폴더의 사진을 합친다`() {
            // given
            val linked = fixtures.사진이_있는_세션()
            val userId = linked.gallery.member.requiredId
            val manualPhoto = photoFixture.업로드된_사진(linked.galleryId, 1).single()
            val manual = service.open(linked.galleryId, userId,
                OpenCollabSessionRequest(name = "직접", photoIds = listOf(manualPhoto, linked.photoId)))
            val otherGallery = fixtures.사진이_있는_세션()

            // when
            val merged = service.open(linked.galleryId, userId, OpenCollabSessionRequest(name = "합친 폴더",
                scope = scope(PhotoScopeType.SESSIONS, sessionIds = listOf(linked.session.sessionId, manual.sessionId))))

            // then
            assertThat(query.listPhotos(linked.galleryId, merged.sessionId, userId, 0, 20).contents.map { it.photoId })
                .containsExactlyInAnyOrder(linked.photoId, manualPhoto)
            assertCode(CollabErrorCode.SESSION_NOT_FOUND) {
                service.open(linked.galleryId, userId, OpenCollabSessionRequest(name = "남의 폴더",
                    scope = scope(PhotoScopeType.SESSIONS, sessionIds = listOf(manual.sessionId, otherGallery.session.sessionId))))
            }
        }

        @Test
        fun `사진 지정 방식이 겹치거나 범위와 목록이 맞지 않으면 공유폴더를 만들지 않는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, 1).single()
            val userId = fixture.member.requiredId
            val before = sessions.count()

            // when & then
            assertCode(CollabErrorCode.INVALID_SELECTION_SOURCE) {
                service.open(fixture.galleryId, userId,
                    OpenCollabSessionRequest(name = "겹침", photoIds = listOf(photoId), scope = scope(PhotoScopeType.ALL)))
            }
            for (invalid in listOf(
                scope(PhotoScopeType.ALL, conceptFolderIds = listOf(1L)),
                scope(PhotoScopeType.ALL, sessionIds = listOf(1L)),
                scope(PhotoScopeType.CONCEPT_FOLDERS),
                scope(PhotoScopeType.DETAIL_FOLDERS, conceptFolderIds = listOf(1L)),
                scope(PhotoScopeType.SESSIONS, conceptFolderIds = listOf(1L), sessionIds = listOf(1L)),
                scope(PhotoScopeType.SESSIONS, sessionIds = List(OpenCollabSessionRequest.PhotoScope.MAX_FOLDER_COUNT + 1) { it + 1L }),
            )) {
                assertCode(CollabErrorCode.INVALID_PHOTO_SCOPE) {
                    service.open(fixture.galleryId, userId, OpenCollabSessionRequest(name = "잘못된 범위", scope = invalid))
                }
            }
            assertThat(sessions.count()).isEqualTo(before)
        }
    }
}

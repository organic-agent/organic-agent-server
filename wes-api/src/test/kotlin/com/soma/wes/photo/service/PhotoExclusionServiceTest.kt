package com.soma.wes.photo.service

import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.CollabPhotoIdsRequest
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.fixture.CollabFixture
import com.soma.wes.collab.service.CollabGuestQueryService
import com.soma.wes.collab.service.CollabGuestService
import com.soma.wes.collab.service.CollabSessionService
import com.soma.wes.folder.service.FolderService
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.PersonalGalleryFixture
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.dto.request.ChangePhotoExclusionsRequest
import com.soma.wes.photo.dto.request.DeletePhotosRequest
import com.soma.wes.photo.dto.request.RatePhotoRequest
import com.soma.wes.photo.dto.request.WritePhotoMemoRequest
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.recommendation.service.AiRecommendationService
import com.soma.wes.selection.dto.request.SelectPhotosRequest
import com.soma.wes.selection.exception.SelectionErrorCode
import com.soma.wes.selection.exception.SelectionException
import com.soma.wes.selection.fixture.SelectionFixture
import com.soma.wes.selection.service.PhotoSelectionExportService
import com.soma.wes.selection.service.PhotoSelectionService
import com.soma.wes.selection.service.SelectionSyncService
import com.soma.wes.support.IntegrationTest
import com.soma.wes.trash.repository.TrashRepository
import com.soma.wes.user.fixture.UserFixture
import jakarta.persistence.EntityManagerFactory
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.ZonedDateTime
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.hibernate.SessionFactory
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@IntegrationTest
class PhotoExclusionServiceTest @Autowired constructor(
    private val exclusions: PhotoExclusionService,
    private val photos: PhotoService,
    private val ratings: PhotoRatingService,
    private val memos: PhotoMemoService,
    private val selections: PhotoSelectionService,
    private val exports: PhotoSelectionExportService,
    private val sync: SelectionSyncService,
    private val folders: FolderService,
    private val guestQuery: CollabGuestQueryService,
    private val guests: CollabGuestService,
    private val sessions: CollabSessionService,
    private val recommendations: AiRecommendationService,
    private val galleries: GalleryFixture,
    private val personalGalleries: PersonalGalleryFixture,
    private val photoFixture: PhotoFixture,
    private val collabFixture: CollabFixture,
    private val selectionFixture: SelectionFixture,
    private val recommendationFixture: RecommendationFixture,
    private val users: UserFixture,
    private val photoRepository: PhotoRepository,
    private val trashRepository: TrashRepository,
    private val jdbc: JdbcClient,
    private val transactions: PlatformTransactionManager,
    private val entityManagers: EntityManagerFactory,
    private val clock: Clock,
    private val dataSource: DataSource,
) {
    @Nested
    @DisplayName("셀렉에서 제외하고 복원할 때")
    inner class State {
        @Test
        fun `선택을 빼고 부부 작가 폴더 기존 공유 추천에서 숨기며 연결과 반응은 보존한다`() {
            // given
            val shared = collabFixture.사진이_있는_세션()
            val galleryId = shared.galleryId
            val memberId = shared.gallery.member.requiredId
            val partner = galleries.멤버(galleryId)
            val photoId = shared.photoId
            val request = ChangePhotoExclusionsRequest(listOf(photoId))
            val selectionId = selectionFixture.셀렉(galleryId)
            selectionFixture.담긴_사진(selectionId, listOf(photoId))
            recommendationFixture.추천(selectionId = selectionId, photoId = photoId)
            ratings.rate(galleryId, photoId, memberId, RatePhotoRequest(score = 1))
            val guest = guests.enter(shared.token, EnterCollabRequest(nickname = "가족"))
            guests.like(shared.token, photoId, guest.guestToken)
            val before = sync.get(galleryId, memberId)

            // when
            assertThat(exclusions.exclude(galleryId, memberId, request).count).isEqualTo(1)

            // then
            assertSoftly { softly ->
                softly.assertThat(selections.get(galleryId, memberId).selectedCount).isZero()
                softly.assertThat(photos.list(galleryId, memberId, null, null, 0, 200).totalCount).isZero()
                softly.assertThat(photos.list(galleryId, shared.gallery.photographer.requiredId, null, null, 0, 200).contents).isEmpty()
                softly.assertThat(folders.list(galleryId, memberId).flatMap { it.details }.flatMap { it.photoIds }).isEmpty()
                softly.assertThat(guestQuery.getLanding(shared.token).photoCount).isZero()
                softly.assertThat(recommendations.list(galleryId, memberId, null).photos).isEmpty()
                softly.assertThat(exclusions.list(galleryId, partner.requiredId, 0, 200).contents.single().score).isEqualTo(1)
                softly.assertThat(exclusions.list(galleryId, shared.gallery.photographer.requiredId, 0, 200).contents.single().score).isNull()
                softly.assertThat(sync.get(galleryId, memberId).exclusionRevision).isGreaterThan(before.exclusionRevision)
            }
            assertThatThrownBy { guests.like(shared.token, photoId, guest.guestToken) }
                .isInstanceOf(CollabException::class.java).extracting("errorCode").isEqualTo(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)
            assertThatThrownBy { selections.select(galleryId, partner.requiredId, SelectPhotosRequest(photoIds = listOf(photoId))) }
                .isInstanceOf(SelectionException::class.java).extracting("errorCode").isEqualTo(SelectionErrorCode.PHOTO_NOT_IN_GALLERY)
            assertThatThrownBy { sessions.addPhotos(galleryId, shared.session.sessionId, memberId, CollabPhotoIdsRequest(listOf(photoId))) }
                .isInstanceOf(CollabException::class.java).extracting("errorCode").isEqualTo(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)

            // when
            assertThat(exclusions.restore(galleryId, partner.requiredId, request).count).isEqualTo(1)

            // then
            assertSoftly { softly ->
                softly.assertThat(selections.get(galleryId, memberId).selectedCount).isZero()
                softly.assertThat(folders.list(galleryId, memberId).flatMap { it.details }.flatMap { it.photoIds }).containsExactly(photoId)
                softly.assertThat(guestQuery.getLanding(shared.token).photoCount).isEqualTo(1L)
                softly.assertThat(guestQuery.listPhotos(shared.token, guest.guestToken, 0, 200).contents.single().likeCount).isEqualTo(1L)
                softly.assertThat(recommendations.list(galleryId, memberId, null).photos.map { it.photo.photoId }).containsExactly(photoId)
                softly.assertThat(ratings.list(galleryId, memberId).single().score).isEqualTo(1)
            }
        }

        @Test
        fun `개인 소유자와 파트너가 함께 제외 복원하고 메모와 파일 정보를 유지한다`() {
            // given
            val gallery = personalGalleries.파트너와_개인_갤러리()
            val photoId = photoFixture.업로드된_사진(gallery.galleryId, count = 1).single()
            val original = photos.get(gallery.galleryId, photoId, gallery.ownerId)
            memos.write(gallery.galleryId, photoId, gallery.ownerId, WritePhotoMemoRequest(content = "확인할 사진"))

            // when
            exclusions.exclude(gallery.galleryId, gallery.partnerId, ChangePhotoExclusionsRequest(listOf(photoId)))
            val hidden = exclusions.list(gallery.galleryId, gallery.ownerId, 0, 200).contents.single()
            exclusions.restore(gallery.galleryId, gallery.ownerId, ChangePhotoExclusionsRequest(listOf(photoId)))

            // then
            assertSoftly { softly ->
                softly.assertThat(hidden.excludedBy).isEqualTo(gallery.partnerId)
                softly.assertThat(hidden.excludedAt).isNotNull()
                softly.assertThat(hidden.memo?.content).isEqualTo("확인할 사진")
                softly.assertThat(hidden.storageKey).isEqualTo(original.storageKey)
                softly.assertThat(photoRepository.countAgainstQuota(gallery.galleryId, clock.instant())).isEqualTo(1L)
                softly.assertThat(exclusions.list(gallery.galleryId, gallery.ownerId, 0, 200).totalCount).isZero()
            }
        }

        @Test
        fun `제외 중 원래 폴더가 삭제되면 복원 후 미분류가 된다`() {
            // given
            val shared = collabFixture.사진이_있는_세션()
            val request = ChangePhotoExclusionsRequest(listOf(shared.photoId))
            exclusions.exclude(shared.galleryId, shared.gallery.member.requiredId, request)
            folders.deleteDetail(shared.galleryId, shared.conceptId, shared.detailId, shared.gallery.photographer.requiredId)

            // when
            exclusions.restore(shared.galleryId, shared.gallery.member.requiredId, request)

            // then
            assertThat(photos.list(shared.galleryId, shared.gallery.member.requiredId, null, null, 0, 200).contents.map { it.photoId }).containsExactly(shared.photoId)
            assertThat(folders.list(shared.galleryId, shared.gallery.member.requiredId).flatMap { it.details }.flatMap { it.photoIds }).isEmpty()
        }

        @Test
        fun `중복 id와 같은 상태 재요청은 변경 번호와 작업자를 덮어쓰지 않는다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val partner = galleries.멤버(gallery.galleryId)
            val id = photoFixture.업로드된_사진(gallery.galleryId, count = 1).single()
            val request = ChangePhotoExclusionsRequest(listOf(id, id))
            assertThat(exclusions.exclude(gallery.galleryId, gallery.member.requiredId, request).count).isEqualTo(1)
            val before = sync.get(gallery.galleryId, partner.requiredId)

            // when
            val repeated = exclusions.exclude(gallery.galleryId, partner.requiredId, request)

            // then
            assertThat(repeated.count).isZero()
            assertThat(sync.get(gallery.galleryId, partner.requiredId)).isEqualTo(before)
            assertThat(exclusions.list(gallery.galleryId, partner.requiredId, 0, 200).contents.single().excludedBy).isEqualTo(gallery.member.requiredId)
            assertThat(exclusions.restore(gallery.galleryId, partner.requiredId, request).count).isEqualTo(1)
            val restored = sync.get(gallery.galleryId, partner.requiredId)
            assertThat(exclusions.restore(gallery.galleryId, partner.requiredId, request).count).isZero()
            assertThat(sync.get(gallery.galleryId, partner.requiredId)).isEqualTo(restored)
        }
    }

    @Nested
    @DisplayName("권한 배치 상태 경계를 검사할 때")
    inner class Validation {
        @Test
        fun `다른 갤러리와 실제 삭제 사진을 섞으면 전체 요청을 롤백한다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val other = galleries.멤버와_열린_갤러리()
            val ids = photoFixture.업로드된_사진(gallery.galleryId, count = 2)
            val foreign = photoFixture.업로드된_사진(other.galleryId, count = 1).single()
            selectionFixture.담긴_사진(selectionFixture.셀렉(gallery.galleryId), ids)
            photos.moveToTrash(gallery.galleryId, gallery.photographer.requiredId, DeletePhotosRequest(listOf(ids.last())))
            val before = sync.get(gallery.galleryId, gallery.member.requiredId)

            // when & then
            for (badId in listOf(foreign, ids.last())) {
                assertThatThrownBy { exclusions.exclude(gallery.galleryId, gallery.member.requiredId, ChangePhotoExclusionsRequest(listOf(ids.first(), badId))) }
                    .isInstanceOf(PhotoException::class.java).extracting("errorCode").isEqualTo(PhotoErrorCode.PHOTO_NOT_FOUND)
            }
            assertThat(exclusions.list(gallery.galleryId, gallery.member.requiredId, 0, 200).contents).isEmpty()
            assertThat(selections.get(gallery.galleryId, gallery.member.requiredId).photos.map { it.photo.photoId }).containsExactly(ids.first())
            assertThat(sync.get(gallery.galleryId, gallery.member.requiredId)).isEqualTo(before)
        }

        @Test
        fun `빈 배치 501장 업로드 미완료 요청을 서비스 경계에서 거절한다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val pending = photoFixture.대기중_사진(gallery.galleryId, count = 1).single()

            // when & then
            val cases = listOf(
                emptyList<Long>() to PhotoErrorCode.EMPTY_EXCLUSION_IDS,
                List(501) { pending } to PhotoErrorCode.TOO_MANY_PHOTOS,
                listOf(pending) to PhotoErrorCode.EXCLUSION_NOT_UPLOADED,
            )
            for ((ids, code) in cases) {
                assertThatThrownBy { exclusions.exclude(gallery.galleryId, gallery.member.requiredId, ChangePhotoExclusionsRequest(ids)) }
                    .isInstanceOf(PhotoException::class.java).extracting("errorCode").isEqualTo(code)
                assertThatThrownBy { exclusions.restore(gallery.galleryId, gallery.member.requiredId, ChangePhotoExclusionsRequest(ids)) }
                    .isInstanceOf(PhotoException::class.java).extracting("errorCode").isEqualTo(code)
            }
        }

        @Test
        fun `작가는 조회만 하고 외부 사용자는 조회와 쓰기를 모두 거절한다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val id = photoFixture.업로드된_사진(gallery.galleryId, count = 1).single()
            val stranger = users.사용자().requiredId
            val request = ChangePhotoExclusionsRequest(listOf(id))

            // when & then
            for (userId in listOf(gallery.photographer.requiredId, stranger)) {
                assertThatThrownBy { exclusions.exclude(gallery.galleryId, userId, request) }
                    .isInstanceOf(GalleryException::class.java).extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
                assertThatThrownBy { exclusions.restore(gallery.galleryId, userId, request) }
                    .isInstanceOf(GalleryException::class.java).extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
            }
            assertThatThrownBy { exclusions.list(gallery.galleryId, stranger, 0, 200) }
                .isInstanceOf(GalleryException::class.java).extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
            assertThat(exclusions.list(gallery.galleryId, gallery.photographer.requiredId, 0, 200).totalCount).isZero()
        }

        @Test
        fun `스튜디오 확정 이후 조회만 가능하고 작가가 다시 열면 복원할 수 있다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리(maxSelectablePhotoCount = 1)
            val ids = photoFixture.업로드된_사진(gallery.galleryId, count = 2)
            val request = ChangePhotoExclusionsRequest(listOf(ids.first()))
            exclusions.exclude(gallery.galleryId, gallery.member.requiredId, request)
            selections.select(gallery.galleryId, gallery.member.requiredId, SelectPhotosRequest(photoIds = listOf(ids.last())))
            selections.submit(gallery.galleryId, gallery.member.requiredId)

            // when & then
            assertThat(exclusions.list(gallery.galleryId, gallery.member.requiredId, 0, 200).totalCount).isEqualTo(1L)
            assertThatThrownBy { exclusions.restore(gallery.galleryId, gallery.member.requiredId, request) }
                .isInstanceOf(PhotoException::class.java).extracting("errorCode").isEqualTo(PhotoErrorCode.EXCLUSIONS_LOCKED)
            assertThatThrownBy { exclusions.exclude(gallery.galleryId, gallery.member.requiredId, ChangePhotoExclusionsRequest(listOf(ids.last()))) }
                .isInstanceOf(PhotoException::class.java).extracting("errorCode").isEqualTo(PhotoErrorCode.EXCLUSIONS_LOCKED)
            selections.withdraw(gallery.galleryId, gallery.photographer.requiredId)
            assertThat(exclusions.restore(gallery.galleryId, gallery.member.requiredId, request).count).isEqualTo(1)
        }

        @Test
        fun `개인 내보내기 후 복원은 잠기고 내보내기를 취소하면 다시 가능하다`() {
            // given
            val gallery = personalGalleries.파트너와_개인_갤러리(target = 1)
            val ids = photoFixture.업로드된_사진(gallery.galleryId, count = 2)
            val request = ChangePhotoExclusionsRequest(listOf(ids.first()))
            exclusions.exclude(gallery.galleryId, gallery.partnerId, request)
            selections.select(gallery.galleryId, gallery.ownerId, SelectPhotosRequest(photoIds = listOf(ids.last())))
            exports.export(gallery.galleryId, gallery.ownerId)

            // when & then
            assertThatThrownBy { exclusions.restore(gallery.galleryId, gallery.partnerId, request) }
                .isInstanceOf(PhotoException::class.java).extracting("errorCode").isEqualTo(PhotoErrorCode.EXCLUSIONS_LOCKED)
            selections.cancelExport(gallery.galleryId, gallery.partnerId)
            assertThat(exclusions.restore(gallery.galleryId, gallery.partnerId, request).count).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("대량 조회와 트랜잭션을 검증할 때")
    inner class Persistence {
        @Test
        fun `500장을 한 번에 제외하고 페이지 총수와 별점 상태 필터가 일치한다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val ids = photoFixture.업로드된_사진(gallery.galleryId, count = 501)
            ratings.rate(gallery.galleryId, ids.first(), gallery.member.requiredId, RatePhotoRequest(score = 5))
            ratings.rate(gallery.galleryId, ids.last(), gallery.member.requiredId, RatePhotoRequest(score = 5))

            // when
            val result = exclusions.exclude(gallery.galleryId, gallery.member.requiredId, ChangePhotoExclusionsRequest(ids.take(500)))

            // then
            assertThat(result.count).isEqualTo(500)
            val page = exclusions.list(gallery.galleryId, gallery.member.requiredId, 2, 200)
            assertSoftly { softly ->
                softly.assertThat(page.totalCount).isEqualTo(500L)
                softly.assertThat(page.contents).hasSize(100)
                softly.assertThat(page.hasNext).isFalse()
                softly.assertThat(photos.list(gallery.galleryId, gallery.member.requiredId, PhotoStatus.UPLOADED, 5, 0, 200).contents.map { it.photoId }).containsExactly(ids.last())
                softly.assertThat(photos.list(gallery.galleryId, gallery.member.requiredId, null, 5, 0, 200).totalCount).isEqualTo(1L)
                softly.assertThat(photoRepository.countAgainstQuota(gallery.galleryId, clock.instant())).isEqualTo(501L)
            }
        }

        @Test
        fun `제외 마이그레이션 재실행은 데이터와 변경 번호를 보존하고 트리거를 중복하지 않는다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val ids = photoFixture.업로드된_사진(gallery.galleryId, count = 1)
            val request = ChangePhotoExclusionsRequest(photoIds = ids)
            exclusions.exclude(gallery.galleryId, gallery.member.requiredId, request)
            val before = sync.get(gallery.galleryId, gallery.member.requiredId)
            val hidden = exclusions.list(gallery.galleryId, gallery.member.requiredId, 0, 200).contents.single()
            val migration = ClassPathResource("db/migration/R__photo_exclusions.sql")
                .getContentAsString(StandardCharsets.UTF_8)

            // when
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute(migration) }
            }

            // then
            assertThat(sync.get(gallery.galleryId, gallery.member.requiredId)).isEqualTo(before)
            val repeated = exclusions.list(gallery.galleryId, gallery.member.requiredId, 0, 200).contents.single()
            assertThat(repeated.excludedBy).isEqualTo(hidden.excludedBy)
            assertThat(repeated.excludedAt).isEqualTo(hidden.excludedAt)
            exclusions.restore(gallery.galleryId, gallery.member.requiredId, request)
            assertThat(sync.get(gallery.galleryId, gallery.member.requiredId).exclusionRevision).isEqualTo(before.exclusionRevision + 1)
        }

        @Test
        fun `보관 기간보다 오래 제외해도 실제 삭제 휴지통과 purge 대상에 들어가지 않는다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val id = photoFixture.업로드된_사진(gallery.galleryId, count = 1).single()
            exclusions.exclude(gallery.galleryId, gallery.member.requiredId, ChangePhotoExclusionsRequest(listOf(id)))
            jdbc.sql("update photos set excluded_at = :at where id = :id")
                .param("at", ZonedDateTime.now(clock).minusDays(30).toOffsetDateTime()).param("id", id).update()

            // when & then
            assertThat(trashRepository.findTrashedPhotos(gallery.galleryId)).isEmpty()
            assertThat(trashRepository.findExpiredPhotoTargets(ZonedDateTime.now(clock))).isEmpty()
            assertThat(photoRepository.countAgainstQuota(gallery.galleryId, clock.instant())).isEqualTo(1L)
        }

        @Test
        fun `외부 트랜잭션 롤백은 제외 선택 해제 변경 번호를 모두 되돌린다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val ids = photoFixture.업로드된_사진(gallery.galleryId, count = 1)
            selectionFixture.담긴_사진(selectionFixture.셀렉(gallery.galleryId), ids)
            val before = sync.get(gallery.galleryId, gallery.member.requiredId)

            // when
            TransactionTemplate(transactions).executeWithoutResult { transaction ->
                exclusions.exclude(gallery.galleryId, gallery.member.requiredId, ChangePhotoExclusionsRequest(ids))
                transaction.setRollbackOnly()
            }

            // then
            assertThat(exclusions.list(gallery.galleryId, gallery.member.requiredId, 0, 200).totalCount).isZero()
            assertThat(selections.get(gallery.galleryId, gallery.member.requiredId).selectedCount).isEqualTo(1)
            assertThat(sync.get(gallery.galleryId, gallery.member.requiredId)).isEqualTo(before)
        }

        @Test
        fun `제외 목록의 쿼리 수는 사진 수에 비례해 늘지 않는다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val ids = photoFixture.업로드된_사진(gallery.galleryId, count = 100)
            exclusions.exclude(gallery.galleryId, gallery.member.requiredId, ChangePhotoExclusionsRequest(ids.take(1)))
            val statistics = entityManagers.unwrap(SessionFactory::class.java).statistics
            val enabled = statistics.isStatisticsEnabled
            statistics.isStatisticsEnabled = true
            try {
                // when
                statistics.clear()
                exclusions.list(gallery.galleryId, gallery.member.requiredId, 0, 200)
                val one = statistics.prepareStatementCount
                exclusions.exclude(gallery.galleryId, gallery.member.requiredId, ChangePhotoExclusionsRequest(ids))
                statistics.clear()
                exclusions.list(gallery.galleryId, gallery.member.requiredId, 0, 200)

                // then
                assertThat(statistics.prepareStatementCount).isEqualTo(one)
            } finally {
                statistics.isStatisticsEnabled = enabled
            }
        }

        @Test
        fun `제외 커밋 전에는 상대에게 이전 목록과 변경 번호가 함께 보인다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val ids = photoFixture.업로드된_사진(gallery.galleryId, count = 1)
            val before = sync.get(gallery.galleryId, gallery.member.requiredId)
            val written = CountDownLatch(1)
            val commit = CountDownLatch(1)
            val pool = Executors.newSingleThreadExecutor()
            try {
                val writing = pool.submit(Callable {
                    TransactionTemplate(transactions).executeWithoutResult {
                        exclusions.exclude(gallery.galleryId, gallery.member.requiredId, ChangePhotoExclusionsRequest(ids))
                        written.countDown()
                        assertThat(commit.await(10, TimeUnit.SECONDS)).isTrue()
                    }
                })

                // when
                assertThat(written.await(10, TimeUnit.SECONDS)).isTrue()

                // then
                assertThat(sync.get(gallery.galleryId, gallery.member.requiredId)).isEqualTo(before)
                assertThat(photos.list(gallery.galleryId, gallery.member.requiredId, null, null, 0, 200).totalCount).isEqualTo(1L)
                commit.countDown()
                writing.get(10, TimeUnit.SECONDS)
                assertThat(sync.get(gallery.galleryId, gallery.member.requiredId).exclusionRevision).isGreaterThan(before.exclusionRevision)
                assertThat(photos.list(gallery.galleryId, gallery.member.requiredId, null, null, 0, 200).totalCount).isZero()
            } finally {
                commit.countDown()
                pool.shutdownNow()
            }
        }

        @Test
        fun `두 참여자의 동시 제외는 한 번만 적용된다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val partner = galleries.멤버(gallery.galleryId)
            val ids = photoFixture.업로드된_사진(gallery.galleryId, count = 1)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val tasks = listOf(gallery.member.requiredId, partner.requiredId).map { userId ->
                    pool.submit(Callable {
                        start.await()
                        exclusions.exclude(gallery.galleryId, userId, ChangePhotoExclusionsRequest(ids)).count
                    })
                }

                // when
                start.countDown()
                val counts = tasks.map { it.get(10, TimeUnit.SECONDS) }

                // then
                assertThat(counts.sorted()).containsExactly(0, 1)
                assertThat(exclusions.list(gallery.galleryId, partner.requiredId, 0, 200).totalCount).isEqualTo(1L)
            } finally {
                pool.shutdownNow()
            }
        }

        @Test
        fun `확정과 제외의 경쟁에서도 확정된 선택은 사후에 바뀌지 않는다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리(maxSelectablePhotoCount = 1)
            val ids = photoFixture.업로드된_사진(gallery.galleryId, count = 1)
            selections.select(gallery.galleryId, gallery.member.requiredId, SelectPhotosRequest(photoIds = ids))
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val submitting = pool.submit(Callable {
                    start.await()
                    try {
                        selections.submit(gallery.galleryId, gallery.member.requiredId)
                        true
                    } catch (e: SelectionException) {
                        assertThat(e.errorCode).isEqualTo(SelectionErrorCode.EMPTY_SELECTION)
                        false
                    }
                })
                val excluding = pool.submit(Callable {
                    start.await()
                    try {
                        exclusions.exclude(gallery.galleryId, gallery.member.requiredId, ChangePhotoExclusionsRequest(ids))
                        true
                    } catch (e: PhotoException) {
                        assertThat(e.errorCode).isEqualTo(PhotoErrorCode.EXCLUSIONS_LOCKED)
                        false
                    }
                })

                // when
                start.countDown()
                val submitted = submitting.get(10, TimeUnit.SECONDS)
                val excluded = excluding.get(10, TimeUnit.SECONDS)

                // then
                assertThat(submitted).isNotEqualTo(excluded)
                assertThat(selections.get(gallery.galleryId, gallery.member.requiredId).selectedCount).isEqualTo(if (submitted) 1 else 0)
                assertThat(exclusions.list(gallery.galleryId, gallery.member.requiredId, 0, 200).totalCount).isEqualTo(if (excluded) 1L else 0L)
            } finally {
                pool.shutdownNow()
            }
        }

        @Test
        fun `동시 선택과 제외 이후 제외 사진은 선택 목록에 남지 않는다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val ids = photoFixture.업로드된_사진(gallery.galleryId, count = 1)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val choosing = pool.submit(Callable {
                    start.await()
                    try {
                        selections.select(gallery.galleryId, gallery.member.requiredId, SelectPhotosRequest(photoIds = ids))
                    } catch (e: SelectionException) {
                        assertThat(e.errorCode).isEqualTo(SelectionErrorCode.PHOTO_NOT_IN_GALLERY)
                    }
                })
                val excluding = pool.submit(Callable {
                    start.await()
                    exclusions.exclude(gallery.galleryId, gallery.member.requiredId, ChangePhotoExclusionsRequest(ids))
                })

                // when
                start.countDown()
                choosing.get(10, TimeUnit.SECONDS)
                excluding.get(10, TimeUnit.SECONDS)

                // then
                assertThat(selections.get(gallery.galleryId, gallery.member.requiredId).selectedCount).isZero()
                assertThat(exclusions.list(gallery.galleryId, gallery.member.requiredId, 0, 200).totalCount).isEqualTo(1L)
            } finally {
                pool.shutdownNow()
            }
        }
    }
}

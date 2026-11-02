package com.soma.wes.selection.service

import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.photo.dto.request.DeletePhotosRequest
import com.soma.wes.photo.dto.request.RatePhotoRequest
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.service.PhotoRatingService
import com.soma.wes.photo.service.PhotoService
import com.soma.wes.selection.dto.request.DeselectPhotosRequest
import com.soma.wes.selection.dto.request.SelectPhotosRequest
import com.soma.wes.support.IntegrationTest
import com.soma.wes.trash.dto.request.RestorePhotosRequest
import com.soma.wes.trash.service.TrashService
import com.soma.wes.user.fixture.UserFixture
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@IntegrationTest
class SelectionSyncServiceTest @Autowired constructor(
    private val sync: SelectionSyncService,
    private val selections: PhotoSelectionService,
    private val ratings: PhotoRatingService,
    private val photos: PhotoService,
    private val trash: TrashService,
    private val galleries: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val users: UserFixture,
    private val jdbc: JdbcClient,
    private val transactionManager: PlatformTransactionManager,
) {
    @Nested
    @DisplayName("변경 번호를 조회할 때")
    inner class Read {
        @Test
        fun `조회 자체는 번호를 바꾸지 않고 별점은 선택 번호와 독립적으로 증가한다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val photoId = photoFixture.업로드된_사진(gallery.galleryId, count = 1).single()
            val before = sync.get(gallery.galleryId, gallery.member.requiredId)
            assertThat(sync.get(gallery.galleryId, gallery.member.requiredId)).isEqualTo(before)

            // when
            ratings.rate(gallery.galleryId, photoId, gallery.member.requiredId, RatePhotoRequest(score = 5))
            val rated = sync.get(gallery.galleryId, gallery.member.requiredId)
            ratings.clear(gallery.galleryId, photoId, gallery.member.requiredId)
            val cleared = sync.get(gallery.galleryId, gallery.member.requiredId)
            ratings.clear(gallery.galleryId, photoId, gallery.member.requiredId)

            // then
            assertSoftly { softly ->
                softly.assertThat(rated.ratingRevision).isGreaterThan(before.ratingRevision)
                softly.assertThat(cleared.ratingRevision).isGreaterThan(rated.ratingRevision)
                softly.assertThat(cleared.selectionRevision).isEqualTo(before.selectionRevision)
                softly.assertThat(sync.get(gallery.galleryId, gallery.member.requiredId)).isEqualTo(cleared)
                softly.assertThat(ratings.list(gallery.galleryId, gallery.member.requiredId)).isEmpty()
            }
        }

        @Test
        fun `담기와 일괄 빼기와 단건 빼기와 재담기를 모두 감지한다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(gallery.galleryId, count = 2)
            val before = sync.get(gallery.galleryId, gallery.member.requiredId)

            // when
            selections.select(gallery.galleryId, gallery.member.requiredId, SelectPhotosRequest(photoIds = photoIds))
            val selected = sync.get(gallery.galleryId, gallery.member.requiredId)
            selections.deselect(gallery.galleryId, gallery.member.requiredId, DeselectPhotosRequest(photoIds = listOf(photoIds[0])))
            val removed = sync.get(gallery.galleryId, gallery.member.requiredId)
            selections.select(gallery.galleryId, gallery.member.requiredId, SelectPhotosRequest(photoIds = listOf(photoIds[0])))
            val reselected = sync.get(gallery.galleryId, gallery.member.requiredId)
            selections.deselectPhoto(gallery.galleryId, photoIds[1], gallery.member.requiredId)
            val singleRemoved = sync.get(gallery.galleryId, gallery.member.requiredId)

            // then
            assertSoftly { softly ->
                softly.assertThat(selected.selectionRevision).isGreaterThan(before.selectionRevision)
                softly.assertThat(removed.selectionRevision).isGreaterThan(selected.selectionRevision)
                softly.assertThat(reselected.selectionRevision).isGreaterThan(removed.selectionRevision)
                softly.assertThat(singleRemoved.selectionRevision).isGreaterThan(reselected.selectionRevision)
                softly.assertThat(singleRemoved.ratingRevision).isEqualTo(before.ratingRevision)
            }
        }

        @Test
        fun `제출과 철회도 감지한다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리(maxSelectablePhotoCount = 1)
            val photoId = photoFixture.업로드된_사진(gallery.galleryId, count = 1).single()
            selections.select(gallery.galleryId, gallery.member.requiredId, SelectPhotosRequest(photoIds = listOf(photoId)))
            val before = sync.get(gallery.galleryId, gallery.member.requiredId)

            // when
            selections.submit(gallery.galleryId, gallery.member.requiredId)
            val submitted = sync.get(gallery.galleryId, gallery.member.requiredId)
            selections.withdraw(gallery.galleryId, gallery.photographer.requiredId)
            val withdrawn = sync.get(gallery.galleryId, gallery.member.requiredId)

            // then
            assertThat(submitted.selectionRevision).isGreaterThan(before.selectionRevision)
            assertThat(withdrawn.selectionRevision).isGreaterThan(submitted.selectionRevision)
        }

        @Test
        fun `갤러리 사이에 변경이 섞이지 않고 작가와 외부 사용자는 조회하지 못한다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val other = galleries.멤버와_열린_갤러리()
            val photoId = photoFixture.업로드된_사진(other.galleryId, count = 1).single()
            val before = sync.get(gallery.galleryId, gallery.member.requiredId)
            val stranger = users.사용자()

            // when
            ratings.rate(other.galleryId, photoId, other.member.requiredId, RatePhotoRequest(score = 4))

            // then
            assertThat(sync.get(gallery.galleryId, gallery.member.requiredId)).isEqualTo(before)
            for (userId in listOf(gallery.photographer.requiredId, stranger.requiredId)) {
                assertThatThrownBy { sync.get(gallery.galleryId, userId) }
                    .isInstanceOf(GalleryException::class.java)
                    .extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
            }
        }
    }

    @Nested
    @DisplayName("DB 트랜잭션과 삭제를 동기화할 때")
    inner class Persistence {
        @Test
        fun `롤백한 별점과 변경 번호는 함께 사라진다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val photoId = photoFixture.업로드된_사진(gallery.galleryId, count = 1).single()
            val before = sync.get(gallery.galleryId, gallery.member.requiredId)

            // when
            TransactionTemplate(transactionManager).executeWithoutResult { transaction ->
                ratings.rate(gallery.galleryId, photoId, gallery.member.requiredId, RatePhotoRequest(score = 5))
                transaction.setRollbackOnly()
            }

            // then
            assertThat(sync.get(gallery.galleryId, gallery.member.requiredId)).isEqualTo(before)
            assertThat(ratings.list(gallery.galleryId, gallery.member.requiredId)).isEmpty()
        }

        @Test
        fun `다른 연결은 커밋 전 변경 번호와 별점을 보지 못한다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val photoId = photoFixture.업로드된_사진(gallery.galleryId, count = 1).single()
            val before = sync.get(gallery.galleryId, gallery.member.requiredId)
            val written = CountDownLatch(1)
            val commit = CountDownLatch(1)

            // when
            Executors.newSingleThreadExecutor().use { executor ->
                val future = executor.submit {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        ratings.rate(gallery.galleryId, photoId, gallery.member.requiredId, RatePhotoRequest(score = 5))
                        written.countDown()
                        check(commit.await(10, TimeUnit.SECONDS)) { "테스트가 커밋 신호를 보내지 않았다" }
                    }
                }
                try {
                    assertThat(written.await(10, TimeUnit.SECONDS)).isTrue()
                    assertThat(sync.get(gallery.galleryId, gallery.member.requiredId)).isEqualTo(before)
                    assertThat(ratings.list(gallery.galleryId, gallery.member.requiredId)).isEmpty()
                } finally {
                    commit.countDown()
                }
                future.get(10, TimeUnit.SECONDS)
            }

            // then
            assertThat(sync.get(gallery.galleryId, gallery.member.requiredId).ratingRevision).isGreaterThan(before.ratingRevision)
            assertThat(ratings.list(gallery.galleryId, gallery.member.requiredId)).hasSize(1)
        }

        @Test
        fun `동시 쓰기가 변경 번호를 잃지 않는다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(gallery.galleryId, count = 4)
            val before = sync.get(gallery.galleryId, gallery.member.requiredId)

            // when
            Executors.newFixedThreadPool(4).use { executor ->
                val futures = photoIds.map { photoId ->
                    executor.submit(Callable {
                        ratings.rate(gallery.galleryId, photoId, gallery.member.requiredId, RatePhotoRequest(score = 2))
                        ratings.rate(gallery.galleryId, photoId, gallery.member.requiredId, RatePhotoRequest(score = 4))
                    })
                }
                for (future in futures) future.get(10, TimeUnit.SECONDS)
            }

            // then
            assertThat(sync.get(gallery.galleryId, gallery.member.requiredId).ratingRevision).isEqualTo(before.ratingRevision + 8)
            assertThat(ratings.list(gallery.galleryId, gallery.member.requiredId).map { it.score }).containsOnly(4)
        }

        @Test
        fun `사진을 숨기고 복원하면 두 목록의 변경을 감지한다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val photoId = photoFixture.업로드된_사진(gallery.galleryId, count = 1).single()
            ratings.rate(gallery.galleryId, photoId, gallery.member.requiredId, RatePhotoRequest(score = 5))
            selections.select(gallery.galleryId, gallery.member.requiredId, SelectPhotosRequest(photoIds = listOf(photoId)))
            val before = sync.get(gallery.galleryId, gallery.member.requiredId)

            // when
            photos.moveToTrash(gallery.galleryId, gallery.photographer.requiredId, DeletePhotosRequest(photoIds = listOf(photoId)))
            val hidden = sync.get(gallery.galleryId, gallery.member.requiredId)
            val hiddenRatings = ratings.list(gallery.galleryId, gallery.member.requiredId)
            trash.restorePhotos(gallery.galleryId, gallery.photographer.requiredId, RestorePhotosRequest(photoIds = listOf(photoId)))
            val restored = sync.get(gallery.galleryId, gallery.member.requiredId)

            // then
            assertSoftly { softly ->
                softly.assertThat(hidden.selectionRevision).isGreaterThan(before.selectionRevision)
                softly.assertThat(hidden.ratingRevision).isGreaterThan(before.ratingRevision)
                softly.assertThat(hiddenRatings).isEmpty()
                softly.assertThat(restored.selectionRevision).isGreaterThan(hidden.selectionRevision)
                softly.assertThat(restored.ratingRevision).isGreaterThan(hidden.ratingRevision)
                softly.assertThat(ratings.list(gallery.galleryId, gallery.member.requiredId)).hasSize(1)
            }
        }

        @Test
        fun `갤러리 물리 삭제에서 변경 번호가 다시 생기지 않는다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val photoId = photoFixture.업로드된_사진(gallery.galleryId, count = 1).single()
            ratings.rate(gallery.galleryId, photoId, gallery.member.requiredId, RatePhotoRequest(score = 5))
            selections.select(gallery.galleryId, gallery.member.requiredId, SelectPhotosRequest(photoIds = listOf(photoId)))

            // when
            jdbc.sql("delete from galleries where id = :id").param("id", gallery.galleryId).update()

            // then
            assertThat(jdbc.sql("select count(*) from gallery_selection_sync where gallery_id = :id")
                .param("id", gallery.galleryId).query(Long::class.java).single()).isZero()
        }
    }
}

package com.soma.wes.gallery.service

import com.soma.wes.auth.config.ReviewLoginProperties
import com.soma.wes.folder.support.AiFolderMaterializer
import com.soma.wes.gallery.domain.GalleryStage
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.PersonalGallery
import com.soma.wes.gallery.fixture.PersonalGalleryFixture
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.gallery.support.GalleryProgressResetter
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.retouch.fixture.RetouchFixture
import com.soma.wes.selection.fixture.SelectionFixture
import com.soma.wes.selection.repository.PhotoSelectionRepository
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate

/**
 * 심사용 계정 id는 설정값이라, 설정마다 컨텍스트를 띄우지 않도록 공용 컨텍스트의 빈으로 서비스를 직접 조립한다.
 */
@IntegrationTest
class ReviewGalleryResetServiceTest @Autowired constructor(
    private val personalGalleryFixture: PersonalGalleryFixture,
    private val photoFixture: PhotoFixture,
    private val recommendationFixture: RecommendationFixture,
    private val selectionFixture: SelectionFixture,
    private val retouchFixture: RetouchFixture,
    private val userFixture: UserFixture,
    private val accessPolicy: GalleryAccessPolicy,
    private val progressResetter: GalleryProgressResetter,
    private val galleryRepository: GalleryRepository,
    private val selectionRepository: PhotoSelectionRepository,
    private val aiFolderMaterializer: AiFolderMaterializer,
    private val jdbc: JdbcClient,
    private val transactionTemplate: TransactionTemplate,
) {

    private fun serviceFor(reviewerId: Long) = ReviewGalleryResetService(
        reviewLoginProperties = ReviewLoginProperties(userId = reviewerId),
        accessPolicy = accessPolicy,
        progressResetter = progressResetter,
    )

    /** 직접 조립한 서비스에는 트랜잭션 프록시가 없어 운영과 같은 한 트랜잭션으로 감싼다. */
    private fun reset(reviewerId: Long, galleryId: Long, userId: Long) =
        checkNotNull(transactionTemplate.execute { serviceFor(reviewerId).reset(galleryId, userId) }) { "초기화 응답이 비었다" }

    private fun count(sql: String, galleryId: Long): Long =
        jdbc.sql(sql).param("galleryId", galleryId).query(Long::class.java).single()

    private fun analyzedGallery(): Pair<PersonalGallery, List<Long>> {
        val gallery = personalGalleryFixture.파트너와_개인_갤러리()
        val photoIds = photoFixture.임베딩된_사진(gallery.galleryId, 3).onEach { photoId ->
            recommendationFixture.분석_결과(photoId = photoId, embedGroupId = 1, burstId = 1)
        }
        val jobId = recommendationFixture.분석_잡(gallery.galleryId)
        recommendationFixture.컨셉_배정(jobId, gallery.galleryId, embedGroupId = 1, conceptName = "야외 자연", detailName = "해변")
        aiFolderMaterializer.materialize(gallery.galleryId)
        return gallery to photoIds
    }

    @Test
    fun `사진과 분석은 남기고 작업 기록을 지운 뒤 AI 폴더를 다시 만든다`() {
        // given
        val (gallery, photoIds) = analyzedGallery()
        val selectionId = selectionFixture.셀렉(gallery.galleryId)
        selectionFixture.담긴_사진(selectionId, photoIds.take(2), addedByUserId = gallery.ownerId)
        selectionFixture.제출(selectionId, gallery.ownerId)
        photoFixture.별점(photoIds[0], score = 5, ratedBy = gallery.ownerId)
        photoFixture.메모_적재(photoIds[1], gallery.ownerId)
        retouchFixture.제출된_회차(gallery.galleryId, photoIds = photoIds.take(1))
        jdbc.sql("UPDATE photos SET excluded_at = now(), excluded_by = :userId WHERE id = :photoId")
            .param("userId", gallery.ownerId).param("photoId", photoIds[2]).update()
        jdbc.sql("UPDATE concept_folders SET name = '바꾼 이름' WHERE gallery_id = :galleryId")
            .param("galleryId", gallery.galleryId).update()

        // when
        val response = reset(gallery.ownerId, gallery.galleryId, gallery.ownerId)

        // then
        val g = gallery.galleryId
        val newSelection = selectionRepository.findByGalleryId(g)
        assertSoftly { softly ->
            softly.assertThat(count("SELECT count(*) FROM photos WHERE gallery_id = :galleryId", g)).isEqualTo(3L)
            softly.assertThat(count("SELECT count(*) FROM photo_analysis a JOIN photos p ON p.id = a.photo_id WHERE p.gallery_id = :galleryId", g)).isEqualTo(3L)
            softly.assertThat(count("SELECT count(*) FROM photo_selection_items WHERE gallery_id = :galleryId", g)).isEqualTo(0L)
            softly.assertThat(count("SELECT count(*) FROM photo_ratings r JOIN photos p ON p.id = r.photo_id WHERE p.gallery_id = :galleryId", g)).isEqualTo(0L)
            softly.assertThat(count("SELECT count(*) FROM photo_memos m JOIN photos p ON p.id = m.photo_id WHERE p.gallery_id = :galleryId", g)).isEqualTo(0L)
            softly.assertThat(count("SELECT count(*) FROM retouch_rounds WHERE gallery_id = :galleryId", g)).isEqualTo(0L)
            softly.assertThat(count("SELECT count(*) FROM photos WHERE gallery_id = :galleryId AND excluded_at IS NOT NULL", g)).isEqualTo(0L)
            softly.assertThat(count("SELECT count(*) FROM concept_folders WHERE gallery_id = :galleryId AND name = '야외 자연'", g)).isEqualTo(1L)
            softly.assertThat(count("SELECT count(*) FROM detail_folder_assignments WHERE gallery_id = :galleryId", g)).isEqualTo(3L)
            softly.assertThat(newSelection?.submittedAt).isNull()
            softly.assertThat(newSelection?.requiredId).isNotEqualTo(selectionId)
            softly.assertThat(response.stage).isEqualTo(GalleryStage.UPLOAD)
        }
    }

    @Test
    fun `분석 전 갤러리는 폴더 없이 초기화한다`() {
        // given
        val gallery = personalGalleryFixture.파트너와_개인_갤러리()
        photoFixture.업로드된_사진(gallery.galleryId, 2)

        // when
        reset(gallery.ownerId, gallery.galleryId, gallery.ownerId)

        // then
        assertThat(count("SELECT count(*) FROM concept_folders WHERE gallery_id = :galleryId", gallery.galleryId)).isEqualTo(0L)
        assertThat(galleryRepository.findByIdOrNull(gallery.galleryId)?.stage).isEqualTo(GalleryStage.UPLOAD)
    }

    @Test
    fun `사진을 모두 휴지통에 넣은 갤러리도 폴더 없이 초기화한다`() {
        // given
        val (gallery, photoIds) = analyzedGallery()
        photoFixture.별점(photoIds[0], score = 4, ratedBy = gallery.ownerId)
        jdbc.sql("UPDATE photos SET deleted_at = now() WHERE gallery_id = :galleryId")
            .param("galleryId", gallery.galleryId).update()

        // when
        reset(gallery.ownerId, gallery.galleryId, gallery.ownerId)

        // then
        val g = gallery.galleryId
        assertThat(count("SELECT count(*) FROM concept_folders WHERE gallery_id = :galleryId", g)).isEqualTo(0L)
        assertThat(count("SELECT count(*) FROM photo_ratings r JOIN photos p ON p.id = r.photo_id WHERE p.gallery_id = :galleryId", g)).isEqualTo(0L)
    }

    @Test
    fun `심사용 계정이 아니면 갤러리 참여자여도 403이다`() {
        // given
        val gallery = personalGalleryFixture.파트너와_개인_갤러리()
        val reviewer = userFixture.사용자("심사위원")

        // when & then
        assertThatThrownBy { reset(reviewer.requiredId, gallery.galleryId, gallery.ownerId) }
            .isInstanceOf(GalleryException::class.java)
            .extracting("errorCode")
            .isEqualTo(GalleryErrorCode.REVIEW_RESET_NOT_ALLOWED)
    }

    @Test
    fun `심사용 계정이어도 남의 갤러리는 초기화하지 못한다`() {
        // given
        val gallery = personalGalleryFixture.파트너와_개인_갤러리()
        val reviewer = userFixture.사용자("심사위원")

        // when & then
        assertThatThrownBy { reset(reviewer.requiredId, gallery.galleryId, reviewer.requiredId) }
            .isInstanceOf(GalleryException::class.java)
            .extracting("errorCode")
            .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
    }
}

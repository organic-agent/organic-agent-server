package com.soma.wes.cluster.service

import com.soma.wes.cluster.exception.ClusterErrorCode
import com.soma.wes.cluster.exception.ClusterException
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * 유사도 임계값으로 사진을 묶는 경로를 서비스 경계에서 확인한다.
 *
 * 벡터를 실제로 DB에 넣고 pgvector의 `<=>`로 거리를 재게 한다. 거리 계산을 흉내 내면
 * 이 기능에서 유일하게 어려운 부분(연산자 의미, 유사도와 거리의 뒤집힌 관계)이 검증에서 빠진다.
 */
@IntegrationTest
class PhotoClusterServiceTest @Autowired constructor(
    private val photoClusterService: PhotoClusterService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val userFixture: UserFixture,
    private val photoRepository: PhotoRepository,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    @Nested
    @DisplayName("사진을 묶을 때")
    inner class Grouping {

        @Test
        fun `임계값 이상으로 닮은 사진끼리 한 묶음이 된다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)

            // 0번과 1번은 유사도 0.95로 닮았고, 2번은 둘 모두와 직교한다(유사도 0).
            val closeAngle = acos(0.95)
            embed(photoIds[0], vectorAt(0.0))
            embed(photoIds[1], vectorAt(closeAngle))
            embed(photoIds[2], vectorAt(Math.PI / 2))

            // when
            val result = photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, 0.9)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.threshold).isEqualTo(0.9)
                softly.assertThat(result.unclassified).isEqualTo(0L)
                // 큰 묶음이 먼저 온다.
                softly.assertThat(result.clusters).hasSize(2)
                softly.assertThat(result.clusters[0].size).isEqualTo(2)
                softly.assertThat(result.clusters[1].size).isEqualTo(1)
                softly.assertThat(result.clusters[1].photos[0].photoId).isEqualTo(photoIds[2])
            }
        }

        @Test
        fun `직접 닮지 않아도 사이에 낀 사진이 있으면 한 묶음이 된다`() {
            // 연결 요소로 묶는다는 결정이 겉으로 드러나는 자리다. 0-1과 1-2는 각각 0.95로 닮았지만
            // 0-2는 0.81까지 떨어진다. 그래도 셋은 같은 인물·장면일 가능성이 높다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)

            val step = acos(0.95)
            embed(photoIds[0], vectorAt(0.0))
            embed(photoIds[1], vectorAt(step))
            embed(photoIds[2], vectorAt(step * 2))

            // when
            val result = photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, 0.9)

            // then
            assertThat(result.clusters).hasSize(1)
            assertThat(result.clusters[0].size).isEqualTo(3)
        }

        @Test
        fun `임베딩이 없는 사진은 묶이지 않고 수로만 나온다`() {
            // 0이 아니면 임베딩 실행이 끝나지 않았다는 뜻이다. 프론트가 "아직 준비 중"을 안내할 근거다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)

            embed(photoIds[0], vectorAt(0.0))

            // when
            val result = photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, null)

            // then
            assertThat(result.clusters).hasSize(1)
            assertThat(result.unclassified).isEqualTo(2L)
        }

        @Test
        fun `묶인 사진에는 서명된 조회 URL이 붙어 온다`() {
            // 비공개 버킷이라 이 URL 없이는 화면에 아무것도 그릴 수 없다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            embed(photoIds[0], vectorAt(0.0))

            // when
            val result = photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, null)

            // then
            assertThat(result.clusters[0].photos[0].viewUrl).contains("X-Amz-Signature")
        }
    }

    @Nested
    @DisplayName("임계값을 다룰 때")
    inner class Threshold {

        @Test
        fun `임계값을 올리면 잘게 쪼개진다`() {
            // 임계값이 사용자에게 주는 손잡이라는 것을 보여주는 자리다. 같은 사진, 같은 벡터인데
            // 값만 바꿔도 결과가 통째로 달라진다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)

            val step = acos(0.95)
            embed(photoIds[0], vectorAt(0.0))
            embed(photoIds[1], vectorAt(step))
            embed(photoIds[2], vectorAt(step * 2))

            // when
            val result = photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, 0.98)

            // then
            assertThat(result.clusters).hasSize(3)
            assertThat(result.clusters[0].size).isEqualTo(1)
        }

        @Test
        fun `임계값을 생략하면 서버 기본값이 쓰이고 응답에 담겨 온다`() {
            // when
            val result = photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, null)

            // then
            assertThat(result.threshold).isEqualTo(0.9)
            assertThat(result.clusters).isEmpty()
        }

        @Test
        fun `임계값이 0에서 1 밖이면 거절한다`() {
            // 컨트롤러의 @Valid에만 맡기지 않는다 — 서비스가 스스로 막아야 다른 호출자도 안전하다.
            // when & then
            assertThatThrownBy {
                photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, 1.5)
            }
                .isInstanceOf(ClusterException::class.java)
                .extracting("errorCode")
                .isEqualTo(ClusterErrorCode.INVALID_THRESHOLD)

            assertThatThrownBy {
                photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, -0.1)
            }
                .isInstanceOf(ClusterException::class.java)
                .extracting("errorCode")
                .isEqualTo(ClusterErrorCode.INVALID_THRESHOLD)
        }
    }

    @Nested
    @DisplayName("접근 권한을 확인할 때")
    inner class Authorization {

        @Test
        fun `초대받은 부부도 클러스터를 볼 수 있다`() {
            // 작가만 보는 화면이 아니다. 고르는 것은 부부의 일이고, 묶음은 고르기 위한 화면이다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            embed(photoIds[0], vectorAt(0.0))

            // when
            val result = photoClusterService.cluster(fixture.galleryId, fixture.member.id!!, null)

            // then
            assertThat(result.clusters).hasSize(1)
        }

        @Test
        fun `갤러리와 무관한 사용자는 볼 수 없다`() {
            // given
            val stranger = userFixture.사용자()

            // when & then
            assertThatThrownBy {
                photoClusterService.cluster(fixture.galleryId, stranger.id!!, null)
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
    }

    // --- helpers ---

    /**
     * 두 축이 만드는 평면 위의 단위 벡터. 두 벡터가 이루는 각의 코사인이 곧 코사인 유사도라,
     * 원하는 유사도를 각도로 바로 지정할 수 있다.
     */
    private fun vectorAt(radians: Double) = FloatArray(Photo.EMBEDDING_DIMENSION).also {
        it[0] = cos(radians).toFloat()
        it[1] = sin(radians).toFloat()
    }

    private fun embed(photoId: Long, vector: FloatArray) {
        val photo = photoRepository.findById(photoId).orElseThrow()
        photo.applyEmbedding(vector)
        photoRepository.saveAndFlush(photo)
    }
}

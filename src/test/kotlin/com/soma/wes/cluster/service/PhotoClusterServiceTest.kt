package com.soma.wes.cluster.service

import com.soma.wes.cluster.exception.ClusterErrorCode
import com.soma.wes.cluster.exception.ClusterException
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.domain.PhotoAnalysis
import com.soma.wes.photo.domain.PhotoMetadata
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import java.time.LocalDateTime
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 레벨 프리셋으로 사진을 묶는 경로를 서비스 경계에서 확인한다.
 *
 * 벡터를 실제로 DB에 넣고 pgvector의 `<=>`로 거리를 재게 한다. 거리 계산을 흉내 내면
 * 이 기능에서 유일하게 어려운 부분(연산자 의미, 유사도와 거리의 뒤집힌 관계, 시간 게이트의
 * SQL 조건)이 검증에서 빠진다.
 *
 * 레벨의 실제 번들 값은 `src/test/resources/application.yml`이 정의한다(운영과 동일).
 * 여기 테스트가 쓰는 유사도들은 그 번들의 경계에 기대므로, 번들을 재선정하면 이 파일의
 * 각도도 함께 손봐야 한다 — 레벨 3 strict 0.92 · lenient 0.82 · window 90초 · knn-k 1,
 * 레벨 5 strict 0.96.
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
        fun `레벨의 기준 이상으로 닮은 사진끼리 한 묶음이 된다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)

            // 0번과 1번은 유사도 0.95로 닮았고(레벨 3의 strict 0.92 이상), 2번은 둘 모두와 직교한다.
            val closeAngle = acos(0.95)
            embed(photoIds[0], vectorAt(0.0))
            embed(photoIds[1], vectorAt(closeAngle))
            embed(photoIds[2], vectorAt(Math.PI / 2))

            // when
            val result = photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, 3)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.level).isEqualTo(3)
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
            val result = photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, 3)

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
    @DisplayName("촬영 시각을 다룰 때")
    inner class TimeGate {

        @Test
        fun `촬영 시각이 가까우면 느슨한 기준으로도 묶인다`() {
            // 유사도 0.85는 레벨 3의 strict(0.92)에 못 미치지만 lenient(0.82)는 넘는다.
            // 시간 창(90초) 안이면 lenient가 적용되어 같은 장면의 다른 프레이밍이 묶인다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)

            val takenAt = LocalDateTime.of(2026, 5, 1, 13, 0, 0)
            embed(photoIds[0], vectorAt(0.0), takenAt)
            embed(photoIds[1], vectorAt(acos(0.85)), takenAt.plusSeconds(30))

            // when
            val result = photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, 3)

            // then
            assertThat(result.clusters).hasSize(1)
            assertThat(result.clusters[0].size).isEqualTo(2)
        }

        @Test
        fun `촬영 시각이 멀면 엄격한 기준만 적용된다`() {
            // 우연히 닮은 다른 장면이 시간까지 가까울 확률은 낮다는 것이 시간 게이트의 전제다.
            // 창 밖의 0.85는 우연으로 취급되어 묶이지 않는다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)

            val takenAt = LocalDateTime.of(2026, 5, 1, 13, 0, 0)
            embed(photoIds[0], vectorAt(0.0), takenAt)
            embed(photoIds[1], vectorAt(acos(0.85)), takenAt.plusMinutes(10))

            // when
            val result = photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, 3)

            // then
            assertThat(result.clusters).hasSize(2)
        }

        @Test
        fun `촬영 시각이 없으면 엄격한 기준만 적용된다`() {
            // EXIF가 없는 사진은 시간 창의 혜택도 배제도 없다 — strict 경로로만 판단한다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)

            embed(photoIds[0], vectorAt(0.0))
            embed(photoIds[1], vectorAt(acos(0.85)))

            // when
            val result = photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, 3)

            // then
            assertThat(result.clusters).hasSize(2)
        }
    }

    @Nested
    @DisplayName("체이닝을 제어할 때")
    inner class ChainControl {

        /**
         * 촘촘한 12장짜리 장면 하나와, 그 끝 사진에 strict 이상(0.94)으로 닮은 다리 사진 하나.
         * 장면 사진마다 다리보다 가까운 이웃이 11개 있으므로, 번들의 k를 어느 후보로
         * 재선정해도(k ≤ 11) 다리는 누구의 상호 이웃도 되지 못한다.
         *
         * @return 다리 사진의 id. 장면은 같은 시각, 다리는 그로부터 [bridgeOffsetSeconds] 뒤에
         *   찍혔다 (null이면 EXIF 없음).
         */
        private fun 장면과_다리(bridgeOffsetSeconds: Long?): Long {
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 13)

            // 이웃 간격을 좁혀 장면에서 가장 먼 쌍도 0.96으로 다리(0.94)보다 가깝게 만든다.
            val step = acos(0.96) / 11
            val sceneTakenAt = LocalDateTime.of(2026, 5, 1, 13, 0, 0)
            photoIds.take(12).forEachIndexed { index, photoId ->
                embed(photoId, vectorAt(index * step), sceneTakenAt)
            }
            val bridgeTakenAt = bridgeOffsetSeconds?.let { sceneTakenAt.plusSeconds(it) }
            embed(photoIds[12], vectorAt(11 * step + acos(0.94)), bridgeTakenAt)

            return photoIds[12]
        }

        @Test
        fun `시간이 먼 다리 사진은 상호 이웃이 아니면 묶이지 못한다`() {
            // 과병합의 주범이 잘리는 자리다. 다리는 장면 끝 사진과 strict를 넘게 닮았지만,
            // 그 사진에게는 자기 장면의 이웃들이 더 가까워 다리를 되받지 않는다.
            // given
            val bridgeId = 장면과_다리(bridgeOffsetSeconds = 600)

            // when
            val result = photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, 3)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.clusters).hasSize(2)
                softly.assertThat(result.clusters[0].size).isEqualTo(12)
                softly.assertThat(result.clusters[1].photos[0].photoId).isEqualTo(bridgeId)
            }
        }

        @Test
        fun `시간이 가까우면 같은 다리도 체이닝으로 묶인다`() {
            // 비대칭 제어의 반대쪽 절반이다. 같은 기하 구조라도 시간 창 안이면 체이닝이
            // recall의 동력이므로 상호 이웃 조건 없이 이어진다.
            // given
            장면과_다리(bridgeOffsetSeconds = 30)

            // when
            val result = photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, 3)

            // then
            assertThat(result.clusters).hasSize(1)
            assertThat(result.clusters[0].size).isEqualTo(13)
        }

        @Test
        fun `촬영 시각을 모르는 다리는 자르지 않는다`() {
            // 필터는 창 밖이 "실측된" 간선만 의심한다. EXIF가 없으면 멀다고 단정할 근거가
            // 없으므로 strict를 넘게 닮은 간선은 그대로 이어진다.
            // given
            장면과_다리(bridgeOffsetSeconds = null)

            // when
            val result = photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, 3)

            // then
            assertThat(result.clusters).hasSize(1)
            assertThat(result.clusters[0].size).isEqualTo(13)
        }
    }

    @Nested
    @DisplayName("레벨을 다룰 때")
    inner class Level {

        @Test
        fun `레벨을 올리면 잘게 쪼개진다`() {
            // 레벨이 사용자에게 주는 손잡이라는 것을 보여주는 자리다. 같은 사진, 같은 벡터인데
            // 레벨만 바꿔도 결과가 통째로 달라진다. 0.95짜리 쌍은 레벨 3(strict 0.92)에서는
            // 묶이지만 레벨 5(strict 0.96)에서는 흩어진다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)

            val step = acos(0.95)
            embed(photoIds[0], vectorAt(0.0))
            embed(photoIds[1], vectorAt(step))
            embed(photoIds[2], vectorAt(step * 2))

            // when
            val result = photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, 5)

            // then
            assertThat(result.clusters).hasSize(3)
            assertThat(result.clusters[0].size).isEqualTo(1)
        }

        @Test
        fun `레벨을 생략하면 서버 기본 레벨이 쓰이고 응답에 담겨 온다`() {
            // when
            val result = photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, null)

            // then
            assertThat(result.level).isEqualTo(3)
            assertThat(result.clusters).isEmpty()
        }

        @Test
        fun `정의되지 않은 레벨이면 거절한다`() {
            // 컨트롤러 검증에만 맡기지 않는다 — 서비스가 스스로 막아야 다른 호출자도 안전하다.
            // when & then
            assertThatThrownBy {
                photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, 0)
            }
                .isInstanceOf(ClusterException::class.java)
                .extracting("errorCode")
                .isEqualTo(ClusterErrorCode.INVALID_LEVEL)

            assertThatThrownBy {
                photoClusterService.cluster(fixture.galleryId, fixture.photographer.id!!, 6)
            }
                .isInstanceOf(ClusterException::class.java)
                .extracting("errorCode")
                .isEqualTo(ClusterErrorCode.INVALID_LEVEL)
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
    private fun vectorAt(radians: Double) = FloatArray(PhotoAnalysis.EMBEDDING_DIMENSION).also {
        it[0] = cos(radians).toFloat()
        it[1] = sin(radians).toFloat()
    }

    private fun embed(photoId: Long, vector: FloatArray, takenAt: LocalDateTime? = null) {
        photoFixture.벡터_적재(photoId, vector)
        takenAt?.let {
            val photo = photoRepository.findById(photoId).orElseThrow()
            photo.applyMetadata(PhotoMetadata(takenAt = it))
            photoRepository.saveAndFlush(photo)
        }
    }
}

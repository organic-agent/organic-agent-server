package com.soma.wes.gallery.support

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.support.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 갤러리 인가 규칙을 한 곳에서 지키는지 확인한다.
 *
 * 여기서 검증하는 건 "작가인가 / 예비 부부인가"가 아니라 "이 갤러리와 어떤 관계인가"다.
 * 전역 종류로 판단하면 통과해버리는 경우(다른 스튜디오 작가, 초대받지 않은 사용자)를 특히 본다.
 */
@DataJpaTest
@Import(TestcontainersConfiguration::class, TimeConfig::class, GalleryAccessPolicy::class)
class GalleryAccessPolicyTest @Autowired constructor(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val studioRepository: StudioRepository,
    private val clock: Clock,
) {

    /** 정책이 `Clock`으로 판단하므로 테스트도 같은 시계에서 기한을 잡는다. */
    private val now: ZonedDateTime get() = ZonedDateTime.now(clock)

    private fun saveStudio(userId: Long): Studio =
        studioRepository.save(
            Studio(userId = userId, name = "스튜디오", galleryUrl = "studio-$userId"),
        )

    private fun saveGallery(
        studio: Studio,
        status: GalleryStatus = GalleryStatus.OPEN,
        selectionDeadline: ZonedDateTime? = null,
    ): Gallery =
        galleryRepository.save(
            Gallery(
                studioId = checkNotNull(studio.id),
                title = "본식",
                status = status,
                selectionDeadline = selectionDeadline,
            ),
        )

    private fun saveMember(galleryId: Long, userId: Long): GalleryMember =
        galleryMemberRepository.save(GalleryMember(galleryId = galleryId, userId = userId))

    private fun galleryId(gallery: Gallery): Long = checkNotNull(gallery.id)

    @Nested
    @DisplayName("갤러리를 다룰 때")
    inner class ManageGallery {

        @Test
        fun `스튜디오 주인은 자기 갤러리를 다룰 수 있다`() {
            // given
            val studio = saveStudio(userId = 10L)
            val gallery = saveGallery(studio)

            // when
            val found = galleryAccessPolicy.requirePhotographer(galleryId(gallery), userId = 10L)

            // then
            assertThat(found.id).isEqualTo(gallery.id)
        }

        @Test
        fun `다른 스튜디오 작가는 갤러리를 다룰 수 없다`() {
            // given
            val studio = saveStudio(userId = 10L)
            saveStudio(userId = 20L)
            val gallery = saveGallery(studio)

            // when & then
            assertThatThrownBy { galleryAccessPolicy.requirePhotographer(galleryId(gallery), userId = 20L) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `스튜디오를 만들지 않은 사용자는 갤러리를 다룰 수 없다`() {
            // 온보딩을 끝내지 않은 작가 계정이 여기 해당한다.
            // given
            val studio = saveStudio(userId = 10L)
            val gallery = saveGallery(studio)

            // when & then
            assertThatThrownBy { galleryAccessPolicy.requirePhotographer(galleryId(gallery), userId = 30L) }
                .isInstanceOf(GalleryException::class.java)
        }

        @Test
        fun `멤버는 갤러리를 다룰 수 없다`() {
            // given
            val studio = saveStudio(userId = 10L)
            val gallery = saveGallery(studio)
            saveMember(galleryId(gallery), userId = 100L)

            // when & then
            assertThatThrownBy { galleryAccessPolicy.requirePhotographer(galleryId(gallery), userId = 100L) }
                .isInstanceOf(GalleryException::class.java)
        }
    }

    @Nested
    @DisplayName("갤러리를 볼 때")
    inner class ViewGallery {

        @Test
        fun `초대로 들어온 멤버는 갤러리를 볼 수 있다`() {
            // given
            val gallery = saveGallery(saveStudio(userId = 10L))
            saveMember(galleryId(gallery), userId = 100L)

            // when
            val found = galleryAccessPolicy.requireViewer(galleryId(gallery), userId = 100L)

            // then
            assertThat(found.id).isEqualTo(gallery.id)
        }

        @Test
        fun `초대받지 않은 사용자는 갤러리를 볼 수 없다`() {
            // given
            val gallery = saveGallery(saveStudio(userId = 10L))

            // when & then
            assertThatThrownBy { galleryAccessPolicy.requireViewer(galleryId(gallery), userId = 777L) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `준비 중인 갤러리는 멤버가 볼 수 없다`() {
            // given
            val gallery = saveGallery(saveStudio(userId = 10L), status = GalleryStatus.DRAFT)
            saveMember(galleryId(gallery), userId = 100L)

            // when & then
            assertThatThrownBy { galleryAccessPolicy.requireViewer(galleryId(gallery), userId = 100L) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `준비 중인 갤러리도 담당 작가는 볼 수 있다`() {
            // given
            val studio = saveStudio(userId = 10L)
            val gallery = saveGallery(studio, status = GalleryStatus.DRAFT)

            // when
            val found = galleryAccessPolicy.requireViewer(galleryId(gallery), userId = 10L)

            // then
            assertThat(found.id).isEqualTo(gallery.id)
        }

        @Test
        fun `마감 기한이 지나도 열람은 계속 된다`() {
            // given
            val gallery = saveGallery(saveStudio(userId = 10L), selectionDeadline = now.minusMinutes(1))
            saveMember(galleryId(gallery), userId = 100L)

            // when
            val found = galleryAccessPolicy.requireViewer(galleryId(gallery), userId = 100L)

            // then
            assertThat(found.id).isEqualTo(gallery.id)
        }

        @Test
        fun `없는 갤러리를 요청하면 404로 응답한다`() {
            // when & then
            assertThatThrownBy { galleryAccessPolicy.requireViewer(galleryId = -1L, userId = 100L) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_NOT_FOUND)
        }
    }

    @Nested
    @DisplayName("사진을 고를 때")
    inner class SelectPhotos {

        @Test
        fun `공개된 갤러리에서 수락한 멤버는 사진을 고를 수 있다`() {
            // given
            val gallery = saveGallery(saveStudio(userId = 10L), status = GalleryStatus.OPEN)
            val saved = saveMember(galleryId(gallery), userId = 100L)

            // when
            val member = galleryAccessPolicy.requireCouple(galleryId(gallery), userId = 100L)

            // then
            assertThat(member.id).isEqualTo(saved.id)
        }

        @Test
        fun `초대로 들어오지 않은 사용자는 사진을 고를 수 없다`() {
            // given
            val gallery = saveGallery(saveStudio(userId = 10L), status = GalleryStatus.OPEN)

            // when & then
            assertThatThrownBy { galleryAccessPolicy.requireCouple(galleryId(gallery), userId = 101L) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `마감 기한 안이면 사진을 고를 수 있다`() {
            // given
            val gallery = saveGallery(saveStudio(userId = 10L), selectionDeadline = now.plusDays(3))
            val saved = saveMember(galleryId(gallery), userId = 100L)

            // when
            val member = galleryAccessPolicy.requireCouple(galleryId(gallery), userId = 100L)

            // then
            assertThat(member.id).isEqualTo(saved.id)
        }

        @Test
        fun `마감 기한이 지나면 상태가 OPEN이어도 사진을 고를 수 없다`() {
            // given
            val gallery = saveGallery(saveStudio(userId = 10L), selectionDeadline = now.minusMinutes(1))
            saveMember(galleryId(gallery), userId = 100L)

            // when & then
            // 아직 안 열린 것과 구분해서 알려줘야 사용자가 연장을 요청할 수 있다.
            assertThatThrownBy { galleryAccessPolicy.requireCouple(galleryId(gallery), userId = 100L) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.SELECTION_DEADLINE_PASSED)
        }

        @Test
        fun `마감된 갤러리에서는 사진을 고를 수 없다`() {
            // given
            val gallery = saveGallery(saveStudio(userId = 10L), status = GalleryStatus.CLOSED)
            saveMember(galleryId(gallery), userId = 100L)

            // when & then
            assertThatThrownBy { galleryAccessPolicy.requireCouple(galleryId(gallery), userId = 100L) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_NOT_OPEN)
        }

        @Test
        fun `담당 작가라도 고객 대신 사진을 고를 수 없다`() {
            // given
            val studio = saveStudio(userId = 10L)
            val gallery = saveGallery(studio, status = GalleryStatus.OPEN)

            // when & then
            assertThatThrownBy { galleryAccessPolicy.requireCouple(galleryId(gallery), userId = 10L) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
    }

    @Nested
    @DisplayName("폴더와 클러스터를 만질 때")
    inner class OrganizeFoldersAndClusters {

        @Test
        fun `작가는 마감이 지나도 폴더와 클러스터를 만질 수 있다`() {
            // 마감은 고객이 고르는 기한이지 작가의 작업 기한이 아니다.
            // given
            val studio = saveStudio(userId = 10L)
            val gallery = saveGallery(studio, status = GalleryStatus.CLOSED, selectionDeadline = now.minusDays(1))

            // when
            val found = galleryAccessPolicy.requirePhotographerOrCouple(galleryId(gallery), userId = 10L)

            // then
            assertThat(found.id).isEqualTo(gallery.id)
        }

        @Test
        fun `부부는 고를 수 있는 동안에만 폴더와 클러스터를 만질 수 있다`() {
            // given
            val gallery = saveGallery(saveStudio(userId = 10L), status = GalleryStatus.OPEN)
            saveMember(galleryId(gallery), userId = 100L)

            // when
            val found = galleryAccessPolicy.requirePhotographerOrCouple(galleryId(gallery), userId = 100L)

            // then
            assertThat(found.id).isEqualTo(gallery.id)
        }

        @Test
        fun `부부는 마감이 지나면 폴더와 클러스터를 만질 수 없다`() {
            // given
            val gallery = saveGallery(saveStudio(userId = 10L), selectionDeadline = now.minusMinutes(1))
            saveMember(galleryId(gallery), userId = 100L)

            // when & then
            assertThatThrownBy { galleryAccessPolicy.requirePhotographerOrCouple(galleryId(gallery), userId = 100L) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.SELECTION_DEADLINE_PASSED)
        }

        @Test
        fun `갤러리와 무관한 사용자는 폴더와 클러스터를 만질 수 없다`() {
            // given
            val gallery = saveGallery(saveStudio(userId = 10L), status = GalleryStatus.OPEN)

            // when & then
            assertThatThrownBy { galleryAccessPolicy.requirePhotographerOrCouple(galleryId(gallery), userId = 999L) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
    }
}

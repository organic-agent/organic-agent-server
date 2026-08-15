package com.soma.wes.collab.support

import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.folder.fixture.FolderFixture
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.ZonedDateTime

/**
 * 세션에 담을 사진의 전부-아니면-거절을 확인한다.
 *
 * 담을 수 있는 것만 담고 나머지를 버리면 화면에는 성공으로 보이고, 어느 사진이 빠졌는지는
 * 아무도 모른다. 잘못된 id 하나가 요청 전체를 거절하는지, 그리고 폴더로 담는 길도 같은
 * 검증을 지나는지를 본다.
 */
@IntegrationTest
class CollabPhotoLoaderTest @Autowired constructor(
    private val collabPhotoLoader: CollabPhotoLoader,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val folderFixture: FolderFixture,
    private val photoRepository: PhotoRepository,
    private val storageProperties: StorageProperties,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    @Nested
    @DisplayName("사진 id로 담을 때")
    inner class LoadByIds {

        @Test
        fun `이 갤러리의 업로드된 사진이면 전부 돌아온다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)

            // when
            val photos = collabPhotoLoader.load(fixture.galleryId, photoIds)

            // then
            assertSoftly { softly ->
                softly.assertThat(photos).hasSize(3)
                softly.assertThat(photos.map { it.requiredId }).containsExactlyInAnyOrderElementsOf(photoIds)
                softly.assertThat(photos).allMatch { it.status == PhotoStatus.UPLOADED }
            }
        }

        @Test
        fun `빈 목록은 거절된다`() {
            // 빈 요청을 통과시키면 아무 일도 하지 않고 성공한다. 200을 받은 화면은 담긴 줄 안다.
            // when & then
            assertThatThrownBy { collabPhotoLoader.load(fixture.galleryId, emptyList()) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.EMPTY_PHOTO_IDS)
        }

        @Test
        fun `배치 상한을 넘는 요청은 거절된다`() {
            // given
            val tooMany = (1..storageProperties.maxBatchSize + 1).map { it.toLong() }

            // when & then
            assertThatThrownBy { collabPhotoLoader.load(fixture.galleryId, tooMany) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.TOO_MANY_PHOTOS)
        }

        @Test
        fun `다른 갤러리의 사진이 섞이면 전체가 거절된다`() {
            // 갤러리 권한만 보고 id를 믿으면 자기 세션으로 남의 사진 서명 URL을 하객에게 내보낸다.
            // given
            val mine = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            val other = galleryFixture.멤버와_열린_갤러리()
            val strangers = photoFixture.업로드된_사진(other.galleryId, count = 1)

            // when & then
            assertThatThrownBy { collabPhotoLoader.load(fixture.galleryId, mine + strangers) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.PHOTO_NOT_IN_GALLERY)
        }

        @Test
        fun `아직 올라오지 않은 사진이 섞이면 전체가 거절된다`() {
            // 실체 없는 사진은 하객 화면에 깨진 이미지로 뜬다. 작가는 올라오는 중인 줄 알지만
            // 하객은 알 도리가 없다.
            // given
            val uploaded = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            val pending = photoFixture.대기중_사진(fixture.galleryId, count = 1)

            // when & then
            assertThatThrownBy { collabPhotoLoader.load(fixture.galleryId, uploaded + pending) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.PHOTO_NOT_UPLOADED)
        }

        @Test
        fun `휴지통에 든 사진은 이 갤러리에 없는 사진과 같은 답을 받는다`() {
            // `@SQLRestriction`이 조회에서 걸러내므로 존재를 따로 확인할 것도 없이 개수가 어긋난다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val trashed = photoRepository.findById(photoIds[0]).orElseThrow()
            trashed.moveToTrash(ZonedDateTime.now())
            photoRepository.saveAndFlush(trashed)

            // when & then
            assertThatThrownBy { collabPhotoLoader.load(fixture.galleryId, photoIds) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.PHOTO_NOT_IN_GALLERY)
        }
    }

    @Nested
    @DisplayName("폴더로 담을 때")
    inner class LoadFromFolder {

        @Test
        fun `폴더에 든 사진이 그대로 온다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)
            val folderId = folderFixture.확정된_폴더(fixture.galleryId, "본식 후보", photoIds.take(2))

            // when
            val photos = collabPhotoLoader.loadFromFolder(fixture.galleryId, folderId)

            // then
            // 폴더에 없던 세 번째 사진은 오지 않는다.
            assertSoftly { softly ->
                softly.assertThat(photos).hasSize(2)
                softly.assertThat(photos.map { it.requiredId }).containsExactlyInAnyOrderElementsOf(photoIds.take(2))
                softly.assertThat(photos.map { it.galleryId }).containsOnly(fixture.galleryId)
            }
        }

        @Test
        fun `다른 갤러리의 폴더는 거절된다`() {
            // 404가 아니라 400이다 — 없다고 알려주면 다른 갤러리의 폴더 id를 응답으로 되짚을 수 있다.
            // given
            val other = galleryFixture.멤버와_열린_갤러리()
            val othersFolderId = folderFixture.확정된_폴더(
                other.galleryId, "남의 묶음", photoFixture.업로드된_사진(other.galleryId, count = 1),
            )

            // when & then
            assertThatThrownBy { collabPhotoLoader.loadFromFolder(fixture.galleryId, othersFolderId) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.FOLDER_NOT_IN_GALLERY)
        }

        @Test
        fun `빈 폴더는 거절된다`() {
            // 빈 세션이 필요하면 folderId 없이 열면 된다. 폴더를 골랐다는 것은 그 사진들을
            // 물어보겠다는 뜻이다.
            // given
            val folderId = folderFixture.확정된_폴더(fixture.galleryId, "비어 있는 묶음", emptyList())

            // when & then
            assertThatThrownBy { collabPhotoLoader.loadFromFolder(fixture.galleryId, folderId) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.EMPTY_FOLDER)
        }
    }
}

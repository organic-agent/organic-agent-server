package com.soma.wes.folder.support

import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.fixture.FolderFixture
import com.soma.wes.folder.repository.PhotoFolderRepository
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.ZonedDateTime

/**
 * 폴더 담기 경로가 전부 지나는 공용 검증을 서포트 경계에서 확인한다.
 *
 * 보는 것은 둘이다: 요청한 사진이 전부 이 갤러리의 살아 있는 사진인지(하나라도 아니면 전체
 * 거절), 그리고 같은 부모 아래 이미 든 사진이 섞였는지.
 */
@IntegrationTest
class FolderPhotoLoaderTest @Autowired constructor(
    private val folderPhotoLoader: FolderPhotoLoader,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val folderFixture: FolderFixture,
    private val photoFolderRepository: PhotoFolderRepository,
    private val photoRepository: PhotoRepository,
    private val properties: StorageProperties,
) {

    private var galleryId: Long = 0L

    @BeforeEach
    fun setUpBaseData() {
        galleryId = galleryFixture.멤버와_열린_갤러리().galleryId
    }

    @Nested
    @DisplayName("사진을 적재할 때")
    inner class LoadPhotos {

        @Test
        fun `요청 순서와 무관하게 노출 순서로 정렬해 돌려준다`() {
            // displayOrder가 같으면 id로 한 번 더 갈린다 — 같은 배치로 올라와 순서가 겹치는
            // 사진들이 화면마다 자리를 바꾸면 안 된다.
            // given
            val firstBatch = photoFixture.업로드된_사진(galleryId, count = 2) // 노출 순서 1, 2
            val secondBatch = photoFixture.업로드된_사진(galleryId, count = 2) // 노출 순서 1, 2
            val shuffled = listOf(secondBatch[1], firstBatch[1], secondBatch[0], firstBatch[0])

            // when
            val result = folderPhotoLoader.loadPhotos(galleryId, shuffled)

            // then
            assertThat(result.map { it.requiredId })
                .containsExactly(firstBatch[0], secondBatch[0], firstBatch[1], secondBatch[1])
        }

        @Test
        fun `같은 id가 여러 번 와도 한 장으로 접어 적재한다`() {
            // 중복을 접지 않으면 요청 수와 조회 결과 수가 어긋나 멀쩡한 요청이 거절된다.
            // given
            val photoIds = photoFixture.업로드된_사진(galleryId, count = 2)

            // when
            val result = folderPhotoLoader.loadPhotos(
                galleryId, listOf(photoIds[0], photoIds[0], photoIds[1]),
            )

            // then
            assertThat(result.map { it.requiredId }).containsExactly(photoIds[0], photoIds[1])
        }

        @Test
        fun `다른 갤러리의 사진이 섞이면 전부 거절한다`() {
            // 갤러리 권한만 확인하고 id를 믿으면 자기 폴더로 남의 사진을 끌어와 서명 URL까지 받아낸다.
            // given
            val myPhotoIds = photoFixture.업로드된_사진(galleryId, count = 1)
            val otherGalleryId = galleryFixture.멤버와_열린_갤러리().galleryId
            val otherPhotoIds = photoFixture.업로드된_사진(otherGalleryId, count = 1)

            // when & then
            assertThatThrownBy { folderPhotoLoader.loadPhotos(galleryId, myPhotoIds + otherPhotoIds) }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode")
                .isEqualTo(FolderErrorCode.PHOTO_NOT_IN_GALLERY)
        }

        @Test
        fun `휴지통에 든 사진은 없는 사진으로 취급해 거절한다`() {
            // 소프트 삭제는 @SQLRestriction이 모든 JPA 조회에서 걸러낸다 — 폴더 담기도 그 필터를 그대로 지난다.
            // given
            val photoIds = photoFixture.업로드된_사진(galleryId, count = 2)
            trashPhoto(photoIds.first())

            // when & then
            assertThatThrownBy { folderPhotoLoader.loadPhotos(galleryId, photoIds) }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode")
                .isEqualTo(FolderErrorCode.PHOTO_NOT_IN_GALLERY)
        }

        @Test
        fun `빈 목록은 거절한다`() {
            // @field:NotEmpty는 컨트롤러를 지날 때만 돈다 — 여기서 막지 않으면 0장짜리 폴더가 남는다.
            // when & then
            assertThatThrownBy { folderPhotoLoader.loadPhotos(galleryId, emptyList()) }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode")
                .isEqualTo(FolderErrorCode.EMPTY_PHOTO_IDS)
        }

        @Test
        fun `배치 상한을 넘으면 조회 전에 거절한다`() {
            // 상한 검사가 조회보다 먼저라, 실재하지 않는 id만으로도 규칙이 확인된다.
            // given
            val tooMany = (1..properties.maxBatchSize + 1).map { it.toLong() }

            // when & then
            assertThatThrownBy { folderPhotoLoader.loadPhotos(galleryId, tooMany) }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode")
                .isEqualTo(FolderErrorCode.TOO_MANY_PHOTOS)
        }
    }

    @Nested
    @DisplayName("부모 스코프 중복을 검사할 때")
    inner class ValidateNoneInGroup {

        @Test
        fun `한 장이라도 부모 아래 어느 자식에 들어 있으면 거절한다`() {
            // 같은 부모 안에서 사진은 한 자식에만 속한다. 겹친 것만 건너뛰면 성공처럼 보이는데
            // 무엇이 왜 빠졌는지 아무도 말할 수 없다.
            // given
            val photoIds = photoFixture.업로드된_사진(galleryId, count = 2)
            val folderId = folderFixture.확정된_폴더(galleryId, "본식", photoIds.take(1))
            val groupId = savedFolder(folderId).groupId

            // when & then
            assertThatThrownBy { folderPhotoLoader.validateNoneInGroup(groupId, photoIds) }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode")
                .isEqualTo(FolderErrorCode.DUPLICATE_PHOTO_IN_GROUP)
        }

        @Test
        fun `다른 부모에 든 사진은 막지 않는다`() {
            // 중복 금지의 단위는 부모다 — 같은 사진이 부모가 다른 묶음에는 얼마든지 든다.
            // given
            val photoIds = photoFixture.업로드된_사진(galleryId, count = 1)
            folderFixture.확정된_폴더(galleryId, "본식", photoIds)
            val emptyFolderId = folderFixture.확정된_폴더(galleryId, "야외", emptyList())
            val otherGroupId = savedFolder(emptyFolderId).groupId

            // when & then
            assertThatCode { folderPhotoLoader.validateNoneInGroup(otherGroupId, photoIds) }
                .doesNotThrowAnyException()
        }
    }

    // --- helpers ---

    /** 픽스처는 folderId만 돌려준다. 부모 id는 저장된 행에서 읽는다. */
    private fun savedFolder(folderId: Long) = photoFolderRepository.findById(folderId).orElseThrow()

    private fun trashPhoto(photoId: Long) {
        val photo = photoRepository.findById(photoId).orElseThrow()
        photo.moveToTrash(ZonedDateTime.now())
        photoRepository.saveAndFlush(photo)
    }
}

package com.soma.wes.folder.support

import com.soma.wes.folder.domain.PhotoFolder
import com.soma.wes.folder.fixture.FolderFixture
import com.soma.wes.folder.repository.PhotoFolderRepository
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.ZonedDateTime

/**
 * 자식폴더 응답 조립의 화면 규칙을 확인한다.
 *
 * 보는 것은 셋이다: 카드(요약)가 사진 수와 노출 순서 첫 장을 대표로 싣는지, 여러 폴더를
 * 한 번에 조립해도 폴더마다 제 것이 담기는지, 상세가 노출 순서와 서명 URL 수명을 싣는지.
 */
@IntegrationTest
class FolderViewAssemblerTest @Autowired constructor(
    private val folderViewAssembler: FolderViewAssembler,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val folderFixture: FolderFixture,
    private val photoFolderRepository: PhotoFolderRepository,
    private val photoRepository: PhotoRepository,
) {

    private var galleryId: Long = 0L

    @BeforeEach
    fun setUpBaseData() {
        galleryId = galleryFixture.멤버와_열린_갤러리().galleryId
    }

    @Nested
    @DisplayName("요약 한 장을 만들 때")
    inner class SummaryOf {

        @Test
        fun `사진 수와 노출 순서 첫 장을 대표로 담는다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(galleryId, count = 3)
            // 담은 순서를 거꾸로 둔다 — 대표는 담은 순서가 아니라 노출 순서가 정한다.
            val folder = savedFolder(folderFixture.확정된_폴더(galleryId, "본식", photoIds.reversed()))

            // when
            val result = folderViewAssembler.summaryOf(folder)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.folderId).isEqualTo(folder.requiredId)
                softly.assertThat(result.groupId).isEqualTo(folder.groupId)
                softly.assertThat(result.name).isEqualTo("본식")
                softly.assertThat(result.photoCount).isEqualTo(3L)
                softly.assertThat(result.coverPhoto!!.photoId).isEqualTo(photoIds.first())
                // 버킷이 비공개라 서명 없는 URL은 화면에서 그려지지 않는다.
                softly.assertThat(result.coverPhoto!!.viewUrl).contains("X-Amz-Signature")
            }
        }

        @Test
        fun `빈 폴더는 대표 사진 없이 0장으로 온다`() {
            // given
            val folder = savedFolder(folderFixture.확정된_폴더(galleryId, "빈 폴더", emptyList()))

            // when
            val result = folderViewAssembler.summaryOf(folder)

            // then
            assertThat(result.photoCount).isEqualTo(0L)
            assertThat(result.coverPhoto).isNull()
        }

        @Test
        fun `휴지통에 든 사진은 세지 않고 대표는 다음 장으로 넘어간다`() {
            // 항목이 id만 들고 있어 존재 여부는 읽는 시점에 확인된다 — 지운 사진이 수에 남으면
            // 카드는 2장이라는데 팝업에는 1장만 보인다.
            // given
            val photoIds = photoFixture.업로드된_사진(galleryId, count = 2)
            val folder = savedFolder(folderFixture.확정된_폴더(galleryId, "본식", photoIds))
            trashPhoto(photoIds.first())

            // when
            val result = folderViewAssembler.summaryOf(folder)

            // then
            assertThat(result.photoCount).isEqualTo(1L)
            assertThat(result.coverPhoto!!.photoId).isEqualTo(photoIds.last())
        }
    }

    @Nested
    @DisplayName("여러 폴더의 요약을 한 번에 만들 때")
    inner class SummariesByFolderId {

        @Test
        fun `폴더마다 제 사진 수와 대표가 담긴다`() {
            // given
            val ceremonyPhotoIds = photoFixture.업로드된_사진(galleryId, count = 2)
            val partyPhotoIds = photoFixture.업로드된_사진(galleryId, count = 1)
            val ceremony = savedFolder(folderFixture.확정된_폴더(galleryId, "본식", ceremonyPhotoIds))
            val party = savedFolder(folderFixture.확정된_폴더(galleryId, "피로연", partyPhotoIds))
            val empty = savedFolder(folderFixture.확정된_폴더(galleryId, "빈 폴더", emptyList()))

            // when
            val result = folderViewAssembler.summariesByFolderId(listOf(ceremony, party, empty))

            // then
            assertSoftly { softly ->
                softly.assertThat(result).hasSize(3)
                softly.assertThat(result[ceremony.requiredId]!!.photoCount).isEqualTo(2L)
                softly.assertThat(result[ceremony.requiredId]!!.coverPhoto!!.photoId)
                    .isEqualTo(ceremonyPhotoIds.first())
                softly.assertThat(result[party.requiredId]!!.photoCount).isEqualTo(1L)
                softly.assertThat(result[party.requiredId]!!.coverPhoto!!.photoId)
                    .isEqualTo(partyPhotoIds.first())
                // 빈 폴더도 응답에서 빠지지 않는다 — 목록에서 사라지면 지워진 것처럼 보인다.
                softly.assertThat(result[empty.requiredId]!!.photoCount).isEqualTo(0L)
                softly.assertThat(result[empty.requiredId]!!.coverPhoto).isNull()
            }
        }

        @Test
        fun `응답 맵은 넘긴 폴더 순서를 지킨다`() {
            // 목록 화면이 이 맵을 돌며 카드를 그린다 — 순서가 뒤집히면 화면이 매번 섞인다.
            // given
            val first = savedFolder(
                folderFixture.확정된_폴더(galleryId, "본식", photoFixture.업로드된_사진(galleryId, count = 1)),
            )
            val second = savedFolder(
                folderFixture.확정된_폴더(galleryId, "피로연", photoFixture.업로드된_사진(galleryId, count = 1)),
            )

            // when
            val result = folderViewAssembler.summariesByFolderId(listOf(second, first))

            // then
            assertThat(result.keys).containsExactly(second.requiredId, first.requiredId)
        }

        @Test
        fun `폴더가 없으면 빈 맵이 온다`() {
            // when
            val result = folderViewAssembler.summariesByFolderId(emptyList())

            // then
            assertThat(result).isEmpty()
        }
    }

    @Nested
    @DisplayName("상세를 만들 때")
    inner class DetailOf {

        @Test
        fun `사진 전부를 노출 순서대로 담고 서명 URL 수명을 알려준다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(galleryId, count = 3)
            val folder = savedFolder(folderFixture.확정된_폴더(galleryId, "본식", photoIds.reversed()))

            // when
            val result = folderViewAssembler.detailOf(folder)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.folderId).isEqualTo(folder.requiredId)
                softly.assertThat(result.groupId).isEqualTo(folder.groupId)
                softly.assertThat(result.name).isEqualTo("본식")
                // 요약의 대표와 같은 정렬이라 카드의 대표 사진과 팝업의 첫 장이 어긋나지 않는다.
                softly.assertThat(result.photos.map { it.photoId }).containsExactlyElementsOf(photoIds)
                softly.assertThat(result.photos.first().viewUrl).contains("X-Amz-Signature")
                // 테스트 설정의 view-url-ttl 15분.
                softly.assertThat(result.viewUrlTtlSeconds).isEqualTo(900L)
            }
        }

        @Test
        fun `사진을 이미 들고 있는 호출자의 목록은 다시 읽지 않고 그대로 조립한다`() {
            // 생성 경로가 방금 검증한 목록을 넘긴다 — 재조회하면 같은 것을 두 번 읽는다.
            // given
            val photoIds = photoFixture.업로드된_사진(galleryId, count = 2)
            val folder = savedFolder(folderFixture.확정된_폴더(galleryId, "본식", photoIds))
            val onlyFirst = listOf(photoRepository.findById(photoIds.first()).orElseThrow())

            // when
            val result = folderViewAssembler.detailOf(folder, onlyFirst)

            // then
            assertThat(result.photos.map { it.photoId }).containsExactly(photoIds.first())
        }

        @Test
        fun `휴지통에 든 사진은 상세에서 빠진다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(galleryId, count = 2)
            val folder = savedFolder(folderFixture.확정된_폴더(galleryId, "본식", photoIds))
            trashPhoto(photoIds.first())

            // when
            val result = folderViewAssembler.detailOf(folder)

            // then
            assertThat(result.photos.map { it.photoId }).containsExactly(photoIds.last())
        }
    }

    // --- helpers ---

    /** 픽스처는 folderId만 돌려준다. 조립자는 엔티티를 받으므로 저장된 행을 되읽는다. */
    private fun savedFolder(folderId: Long): PhotoFolder =
        photoFolderRepository.findById(folderId).orElseThrow()

    private fun trashPhoto(photoId: Long) {
        val photo = photoRepository.findById(photoId).orElseThrow()
        photo.moveToTrash(ZonedDateTime.now())
        photoRepository.saveAndFlush(photo)
    }
}

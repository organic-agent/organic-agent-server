package com.soma.wes.folder.service

import com.soma.wes.folder.dto.request.AddPhotosRequest
import com.soma.wes.folder.dto.request.CreateFolderGroupRequest
import com.soma.wes.folder.dto.request.CreatePhotoFolderRequest
import com.soma.wes.folder.dto.request.MovePhotosRequest
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.repository.PhotoFolderGroupRepository
import com.soma.wes.folder.repository.PhotoFolderItemRepository
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 부모폴더 아래 자식폴더의 생성·조회·사진 담기·옮기기·빼기·삭제를 서비스 경계에서 확인한다.
 *
 * 폴더는 예비 부부의 것이라, 여기 나오는 요청은 대부분 초대받은 멤버가 보낸다.
 */
@IntegrationTest
class PhotoFolderServiceTest @Autowired constructor(
    private val photoFolderService: PhotoFolderService,
    private val photoFolderGroupService: PhotoFolderGroupService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val photoRepository: PhotoRepository,
    private val photoFolderGroupRepository: PhotoFolderGroupRepository,
    private val photoFolderItemRepository: PhotoFolderItemRepository,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    @Nested
    @DisplayName("자식폴더를 만들 때")
    inner class Create {

        @Test
        fun `빈 부모를 만들고 그 아래 빈 자식을 만든다`() {
            // 수동 흐름. 드래그로 채워 넣는 UX가 빈 폴더에서 시작한다.
            // given
            val groupId = createGroup(fixture, "직접 만든 부모")

            // when
            val result = photoFolderService.create(
                fixture.galleryId, groupId, fixture.member.id!!, CreatePhotoFolderRequest(name = "빈 폴더"),
            )

            // then
            assertThat(result.groupId).isEqualTo(groupId)
            assertThat(result.photos).isEmpty()
        }
    }

    @Nested
    @DisplayName("자식폴더를 조회할 때")
    inner class Read {

        @Test
        fun `자식폴더는 만든 시점의 목록을 고정한다`() {
            // 사진이 더 올라와도 이미 만든 폴더는 흔들리면 안 된다 -- 폴더는 클러스터를
            // 가리키는 포인터가 아니라 확정한 목록이다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val (groupId, folderId) = createGroupWithFolder(fixture, "본식", "묶음 1", photoIds)

            photoFixture.업로드된_사진(fixture.galleryId, count = 3)

            // when
            val result = photoFolderService.get(fixture.galleryId, groupId, folderId, fixture.member.id!!)

            // then
            assertThat(result.photos).hasSize(2)
        }
    }

    @Nested
    @DisplayName("사진을 추가·이동·제거할 때")
    inner class ManagePhotos {

        @Test
        fun `같은 부모의 다른 자식에 이미 든 사진은 담을 수 없다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val (groupId, _) = createGroupWithFolder(fixture, "본식", "묶음 1", photoIds.take(1))
            val emptyFolderId = createFolder(fixture, groupId, "묶음 2", photoIds.drop(1))

            // when & then
            assertThatThrownBy {
                photoFolderService.addPhotos(
                    fixture.galleryId,
                    groupId,
                    emptyFolderId,
                    fixture.member.id!!,
                    AddPhotosRequest(listOf(photoIds.first())),
                )
            }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode")
                .isEqualTo(FolderErrorCode.DUPLICATE_PHOTO_IN_GROUP)

            // 전체 거절이라 항목 수가 그대로다.
            assertThat(photoFolderItemRepository.countByFolderId(emptyFolderId)).isEqualTo(1L)
        }

        @Test
        fun `사진을 같은 부모의 다른 자식으로 옮긴다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)
            val (groupId, sourceId) = createGroupWithFolder(fixture, "본식", "묶음 1", photoIds)
            val targetId = createFolder(fixture, groupId, "묶음 2", emptyList())

            // when
            val result = photoFolderService.movePhotos(
                fixture.galleryId,
                groupId,
                sourceId,
                fixture.member.id!!,
                MovePhotosRequest(targetFolderId = targetId, photoIds = photoIds.take(2)),
            )

            // then
            assertSoftly { softly ->
                // 응답은 사진이 도착한 폴더의 상세다.
                softly.assertThat(result.folderId).isEqualTo(targetId)
                softly.assertThat(result.photos).hasSize(2)
                softly.assertThat(photoFolderItemRepository.countByFolderId(sourceId)).isEqualTo(1L)
                softly.assertThat(photoFolderItemRepository.countByFolderId(targetId)).isEqualTo(2L)
            }
        }

        @Test
        fun `출발지에 없는 사진은 옮길 수 없다`() {
            // 일부만 옮기면 성공처럼 보이는데 무엇이 빠졌는지 아무도 말할 수 없다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val (groupId, sourceId) = createGroupWithFolder(fixture, "본식", "묶음 1", photoIds.take(1))
            val targetId = createFolder(fixture, groupId, "묶음 2", emptyList())

            // when & then
            assertThatThrownBy {
                photoFolderService.movePhotos(
                    fixture.galleryId,
                    groupId,
                    sourceId,
                    fixture.member.id!!,
                    MovePhotosRequest(targetFolderId = targetId, photoIds = photoIds),
                )
            }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode")
                .isEqualTo(FolderErrorCode.PHOTO_NOT_IN_FOLDER)

            assertThat(photoFolderItemRepository.countByFolderId(sourceId)).isEqualTo(1L)
            assertThat(photoFolderItemRepository.countByFolderId(targetId)).isEqualTo(0L)
        }

        @Test
        fun `다른 부모의 자식으로는 옮길 수 없다`() {
            // 이동은 같은 부모를 공유하는 자식들 사이에서만 허용한다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val (groupId, sourceId) = createGroupWithFolder(fixture, "첫 부모", "묶음", photoIds.take(1))
            val (_, foreignFolderId) = createGroupWithFolder(fixture, "둘째 부모", "묶음", photoIds.drop(1))

            // when & then
            assertThatThrownBy {
                photoFolderService.movePhotos(
                    fixture.galleryId,
                    groupId,
                    sourceId,
                    fixture.member.id!!,
                    MovePhotosRequest(targetFolderId = foreignFolderId, photoIds = listOf(photoIds.first())),
                )
            }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode")
                .isEqualTo(FolderErrorCode.FOLDER_NOT_FOUND)
        }

        @Test
        fun `자식폴더에서 사진을 뺀다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val (groupId, folderId) = createGroupWithFolder(fixture, "본식", "묶음 1", photoIds)

            // when
            photoFolderService.removePhoto(
                fixture.galleryId, groupId, folderId, photoIds.first(), fixture.member.id!!,
            )

            // then
            assertThat(photoFolderItemRepository.countByFolderId(folderId)).isEqualTo(1L)
            // 폴더에서만 빠진다. 사진 자체는 갤러리에 그대로 있다.
            assertThat(photoRepository.countByGalleryId(fixture.galleryId)).isEqualTo(2L)
        }

        @Test
        fun `폴더에 없는 사진을 빼면 실패한다`() {
            // 조용히 성공시키면 프론트는 지운 줄 알고 화면에서 지운다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val (groupId, folderId) = createGroupWithFolder(fixture, "본식", "묶음 1", photoIds.take(1))

            // when & then
            assertThatThrownBy {
                photoFolderService.removePhoto(
                    fixture.galleryId, groupId, folderId, photoIds.last(), fixture.member.id!!,
                )
            }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode")
                .isEqualTo(FolderErrorCode.PHOTO_NOT_IN_FOLDER)
        }
    }

    @Nested
    @DisplayName("자식폴더를 지울 때")
    inner class Delete {

        @Test
        fun `자식폴더를 지워도 부모와 사진은 남는다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val (groupId, folderId) = createGroupWithFolder(fixture, "본식", "묶음 1", photoIds)

            // when
            photoFolderService.delete(fixture.galleryId, groupId, folderId, fixture.member.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(photoFolderGroupRepository.count()).isEqualTo(1L)
                softly.assertThat(photoFolderItemRepository.countByFolderId(folderId)).isEqualTo(0L)
                softly.assertThat(photoRepository.countByGalleryId(fixture.galleryId)).isEqualTo(2L)
            }
        }
    }

    // --- helpers ---

    private fun createGroup(fixture: OpenGallery, name: String): Long =
        photoFolderGroupService.create(
            fixture.galleryId, fixture.member.id!!, CreateFolderGroupRequest(name = name),
        ).groupId

    /** 자식폴더 하나짜리 부모를 만들고 (groupId, folderId)를 돌려준다. */
    private fun createGroupWithFolder(
        fixture: OpenGallery,
        groupName: String,
        folderName: String,
        photoIds: List<Long>,
    ): Pair<Long, Long> {
        val result = photoFolderGroupService.create(
            fixture.galleryId,
            fixture.member.id!!,
            CreateFolderGroupRequest(
                name = groupName,
                folders = listOf(CreateFolderGroupRequest.FolderRequest(name = folderName, photoIds = photoIds)),
            ),
        )
        return result.groupId to result.folders.first().folderId
    }

    private fun createFolder(fixture: OpenGallery, groupId: Long, name: String, photoIds: List<Long>): Long =
        photoFolderService.create(
            fixture.galleryId,
            groupId,
            fixture.member.id!!,
            CreatePhotoFolderRequest(name = name, photoIds = photoIds),
        ).folderId
}

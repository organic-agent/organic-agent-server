package com.soma.wes.folder.service

import com.soma.wes.folder.dto.request.CreateFolderGroupRequest
import com.soma.wes.folder.dto.request.RenameFolderGroupRequest
import com.soma.wes.folder.dto.request.RenamePhotoFolderRequest
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.repository.PhotoFolderGroupRepository
import com.soma.wes.folder.repository.PhotoFolderItemRepository
import com.soma.wes.folder.repository.PhotoFolderRepository
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
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
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 부모폴더(클러스터 고정)의 생성·목록·이름 변경·삭제를 서비스 경계에서 확인한다.
 *
 * 폴더는 예비 부부의 것이라, 여기 나오는 요청은 대부분 초대받은 멤버가 보낸다.
 */
@IntegrationTest
class PhotoFolderGroupServiceTest @Autowired constructor(
    private val photoFolderGroupService: PhotoFolderGroupService,
    private val photoFolderService: PhotoFolderService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val photoRepository: PhotoRepository,
    private val photoFolderGroupRepository: PhotoFolderGroupRepository,
    private val photoFolderRepository: PhotoFolderRepository,
    private val photoFolderItemRepository: PhotoFolderItemRepository,
    private val jdbcClient: JdbcClient,
    private val transactionTemplate: TransactionTemplate,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    @Nested
    @DisplayName("부모폴더를 만들 때")
    inner class Create {

        @Test
        fun `클러스터링 결과를 부모폴더 하나로 고정한다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 4)

            // when
            val result = photoFolderGroupService.create(
                fixture.galleryId,
                fixture.member.id!!,
                CreateFolderGroupRequest(
                    name = "  본식  ",
                    folders = listOf(
                        CreateFolderGroupRequest.FolderRequest(name = "묶음 1", photoIds = photoIds.take(2)),
                        CreateFolderGroupRequest.FolderRequest(name = "묶음 2", photoIds = photoIds.drop(2)),
                    ),
                ),
            )

            // then
            assertSoftly { softly ->
                // 앞뒤 공백은 떼고 저장한다.
                softly.assertThat(result.name).isEqualTo("본식")
                softly.assertThat(result.folders).hasSize(2)
                softly.assertThat(result.folders[0].name).isEqualTo("묶음 1")
                softly.assertThat(result.folders[0].photoCount).isEqualTo(2L)
                softly.assertThat(result.folders[0].coverPhoto?.viewUrl).contains("X-Amz-Signature")
            }
            assertThat(photoFolderItemRepository.countByFolderId(result.folders[0].folderId)).isEqualTo(2L)
        }

        @Test
        fun `묶음 간에 사진이 겹치면 전체가 거절되고 부모도 남지 않는다`() {
            // 같은 부모 아래 사진 중복 금지. 일부만 조용히 건너뛰면 성공처럼 보이는데
            // 무엇이 왜 빠졌는지 아무도 말할 수 없다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)

            // when & then
            assertThatThrownBy {
                photoFolderGroupService.create(
                    fixture.galleryId,
                    fixture.member.id!!,
                    CreateFolderGroupRequest(
                        name = "본식",
                        folders = listOf(
                            CreateFolderGroupRequest.FolderRequest(name = "묶음 1", photoIds = photoIds),
                            CreateFolderGroupRequest.FolderRequest(
                                name = "묶음 2",
                                photoIds = listOf(photoIds.first()),
                            ),
                        ),
                    ),
                )
            }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode")
                .isEqualTo(FolderErrorCode.DUPLICATE_PHOTO_IN_GROUP)

            // 검증이 저장보다 먼저라 이름뿐인 빈 부모가 남지 않는다.
            assertThat(photoFolderGroupRepository.count()).isEqualTo(0L)
        }

        @Test
        fun `서로 다른 부모끼리는 같은 사진을 담을 수 있다`() {
            // 중복 금지의 범위는 부모 하나다. 다른 부모는 서로 신경 쓸 필요가 없다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            createGroupWithFolder(fixture, "첫 부모", "묶음", photoIds)

            // when
            val result = photoFolderGroupService.create(
                fixture.galleryId,
                fixture.member.id!!,
                CreateFolderGroupRequest(
                    name = "둘째 부모",
                    folders = listOf(CreateFolderGroupRequest.FolderRequest(name = "묶음", photoIds = photoIds)),
                ),
            )

            // then
            assertThat(result.folders[0].photoCount).isEqualTo(1L)
        }
    }

    @Nested
    @DisplayName("부모폴더 목록을 조회할 때")
    inner class Read {

        @Test
        fun `부모 목록은 자식 요약까지 한 번에 준다`() {
            // 좌측 폴더 메뉴를 이 응답 하나로 그린다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)
            createGroupWithFolder(fixture, "첫 부모", "묶음", photoIds.take(1))
            createGroupWithFolder(fixture, "둘째 부모", "묶음", photoIds.drop(1))

            // when
            val result = photoFolderGroupService.list(fixture.galleryId, fixture.member.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(result).hasSize(2)
                // 최근에 만든 부모가 먼저다.
                softly.assertThat(result[0].name).isEqualTo("둘째 부모")
                softly.assertThat(result[0].folders[0].photoCount).isEqualTo(2L)
                softly.assertThat(result[1].folders[0].photoCount).isEqualTo(1L)
                softly.assertThat(result[0].folders[0].coverPhoto?.viewUrl).contains("X-Amz-Signature")
            }
        }
    }

    @Nested
    @DisplayName("이름을 바꿀 때")
    inner class Rename {

        @Test
        fun `부모와 자식의 이름을 바꾼다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            val (groupId, folderId) = createGroupWithFolder(fixture, "본식", "묶음 1", photoIds)

            // when
            val renamedGroup = photoFolderGroupService.rename(
                fixture.galleryId, groupId, fixture.member.id!!, RenameFolderGroupRequest("본식 (최종)"),
            )
            val renamedFolder = photoFolderService.rename(
                fixture.galleryId, groupId, folderId, fixture.member.id!!, RenamePhotoFolderRequest("신부 단독"),
            )

            // then
            assertSoftly { softly ->
                softly.assertThat(renamedGroup.name).isEqualTo("본식 (최종)")
                softly.assertThat(renamedFolder.name).isEqualTo("신부 단독")
                softly.assertThat(renamedFolder.photoCount).isEqualTo(1L)
            }
        }
    }

    @Nested
    @DisplayName("부모폴더를 지울 때")
    inner class Delete {

        @Test
        fun `부모를 지우면 자식과 항목까지 사라지고 사진은 남는다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val (groupId, folderId) = createGroupWithFolder(fixture, "본식", "묶음 1", photoIds)

            // when
            photoFolderGroupService.delete(fixture.galleryId, groupId, fixture.member.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(photoFolderGroupRepository.count()).isEqualTo(0L)
                softly.assertThat(photoFolderRepository.count()).isEqualTo(0L)
                softly.assertThat(photoFolderItemRepository.countByFolderId(folderId)).isEqualTo(0L)
                // 사진 자체는 갤러리에 그대로 남는다.
                softly.assertThat(photoRepository.countByGalleryId(fixture.galleryId)).isEqualTo(2L)
            }
        }
    }

    @Nested
    @DisplayName("관리자 목업 재계산과 경쟁할 때")
    inner class Concurrency {

        @Test
        fun `부모 이름 변경과 삭제는 부모 행 잠금 뒤 실행된다`() {
            val renameGroup = createGroup(fixture, "기존 부모")
            assertWaitsForGroupLock(renameGroup) {
                photoFolderGroupService.rename(
                    fixture.galleryId,
                    renameGroup,
                    fixture.member.id!!,
                    RenameFolderGroupRequest("바뀐 부모"),
                )
            }

            val deleteGroup = createGroup(fixture, "삭제할 부모")
            assertWaitsForGroupLock(deleteGroup) {
                photoFolderGroupService.delete(fixture.galleryId, deleteGroup, fixture.member.id!!)
            }

            assertThat(photoFolderGroupService.get(
                fixture.galleryId, renameGroup, fixture.member.id!!,
            ).name).isEqualTo("바뀐 부모")
            assertThat(photoFolderGroupRepository.findById(deleteGroup)).isEmpty
        }
    }

    @Nested
    @DisplayName("권한과 경계를 확인할 때")
    inner class Authorization {

        @Test
        fun `다른 갤러리의 사진으로는 고정할 수 없다`() {
            // 갤러리 권한만 보고 사진 id를 믿으면, 자기 갤러리에 만든 폴더로 남의 사진을 끌어와
            // 서명 URL까지 받아낼 수 있다.
            // given
            val otherFixture = galleryFixture.멤버와_열린_갤러리()
            val otherPhotoIds = photoFixture.업로드된_사진(otherFixture.galleryId, count = 1)

            // when & then
            assertThatThrownBy {
                photoFolderGroupService.create(
                    fixture.galleryId,
                    fixture.member.id!!,
                    CreateFolderGroupRequest(
                        name = "남의 사진",
                        folders = listOf(
                            CreateFolderGroupRequest.FolderRequest(name = "묶음", photoIds = otherPhotoIds),
                        ),
                    ),
                )
            }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode")
                .isEqualTo(FolderErrorCode.PHOTO_NOT_IN_GALLERY)
        }

        @Test
        fun `다른 갤러리의 부모폴더 id로는 접근할 수 없다`() {
            // 인가는 galleryId로 확인한다. 부모를 id만으로 찾으면 그 확인이 무의미해진다.
            // given
            val otherFixture = galleryFixture.멤버와_열린_갤러리()
            val otherGroupId = createGroup(otherFixture, "남의 부모")

            // when & then
            assertThatThrownBy {
                photoFolderGroupService.get(fixture.galleryId, otherGroupId, fixture.member.id!!)
            }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode")
                .isEqualTo(FolderErrorCode.GROUP_NOT_FOUND)
        }

        @Test
        fun `선택 마감이 지나면 부부는 폴더를 만들 수 없다`() {
            // given
            photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            galleryFixture.마감_지남(fixture.galleryId)

            // when & then
            assertThatThrownBy {
                photoFolderGroupService.create(
                    fixture.galleryId, fixture.member.id!!, CreateFolderGroupRequest(name = "늦은 부모"),
                )
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.SELECTION_DEADLINE_PASSED)
        }

        @Test
        fun `선택 마감이 지나도 작가는 폴더를 만질 수 있다`() {
            // 마감은 고객이 고르는 기한이지 작가의 작업 기한이 아니다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            val (groupId, _) = createGroupWithFolder(fixture, "본식", "묶음", photoIds)
            galleryFixture.마감_지남(fixture.galleryId)

            // when & then
            val result = photoFolderGroupService.get(fixture.galleryId, groupId, fixture.photographer.id!!)
            assertThat(result.groupId).isEqualTo(groupId)

            assertThatThrownBy { photoFolderGroupService.list(fixture.galleryId, fixture.member.id!!) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.SELECTION_DEADLINE_PASSED)
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

    private fun assertWaitsForGroupLock(groupId: Long, action: () -> Unit) {
        val lockAcquired = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val lockFuture = pool.submit<Unit> {
                transactionTemplate.executeWithoutResult {
                    jdbcClient.sql("SELECT id FROM photo_folder_groups WHERE id=:id FOR UPDATE")
                        .param("id", groupId).query { rs, _ -> rs.getLong("id") }.single()
                    lockAcquired.countDown()
                    check(allowCommit.await(30, TimeUnit.SECONDS))
                }
            }
            assertThat(lockAcquired.await(10, TimeUnit.SECONDS)).isTrue()

            val actionFuture = pool.submit<Unit> { action() }
            assertThat(waitForGroupRowLock()).isTrue()

            allowCommit.countDown()
            lockFuture.get(10, TimeUnit.SECONDS)
            actionFuture.get(10, TimeUnit.SECONDS)
        } finally {
            allowCommit.countDown()
            pool.shutdownNow()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    private fun waitForGroupRowLock(): Boolean {
        repeat(400) {
            val waiting = jdbcClient.sql(
                """
                SELECT EXISTS (
                    SELECT 1 FROM pg_stat_activity
                    WHERE datname=current_database()
                      AND pid<>pg_backend_pid()
                      AND state='active'
                      AND wait_event_type='Lock'
                      AND query ILIKE '%photo_folder_groups%'
                      AND query ILIKE '%gallery_id%'
                )
                """.trimIndent(),
            ).query { rs, _ -> rs.getBoolean(1) }.single()
            if (waiting) return true
            Thread.sleep(25)
        }
        return false
    }
}

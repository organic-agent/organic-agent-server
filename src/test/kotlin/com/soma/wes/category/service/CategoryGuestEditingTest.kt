package com.soma.wes.category.service

import com.soma.wes.category.domain.CategorySource
import com.soma.wes.category.dto.request.CreateConceptFolderRequest
import com.soma.wes.category.dto.request.CreateDetailFolderRequest
import com.soma.wes.category.dto.request.MoveCategoryPhotosRequest
import com.soma.wes.category.exception.CategoryErrorCode
import com.soma.wes.category.exception.CategoryException
import com.soma.wes.category.repository.PhotoCategoryAssignmentRepository
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.selection.dto.request.SelectPhotosRequest
import com.soma.wes.selection.exception.SelectionErrorCode
import com.soma.wes.selection.exception.SelectionException
import com.soma.wes.selection.service.PhotoSelectionService
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@IntegrationTest
class CategoryGuestEditingTest @Autowired constructor(
    private val categoryService: CategoryService,
    private val selectionService: PhotoSelectionService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val userFixture: UserFixture,
    private val galleryRepository: GalleryRepository,
    private val assignmentRepository: PhotoCategoryAssignmentRepository,
    private val photoRepository: PhotoRepository,
    private val transactionManager: PlatformTransactionManager,
) {
    @Nested
    @DisplayName("초대 고객이 카테고리를 편집할 때")
    inner class Editing {
        @Test
        fun `컨셉과 세부폴더를 만들고 사진을 다른 컨셉과 미분류로 옮기며 폴더를 삭제한다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, 2)
            val userId = fixture.member.requiredId
            val first = categoryService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("실내"))
            val second = categoryService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("야외"))
            val firstDetail = categoryService.createDetail(
                fixture.galleryId, first.id, userId, CreateDetailFolderRequest("함께"),
            )
            val secondDetail = categoryService.createDetail(
                fixture.galleryId, second.id, userId, CreateDetailFolderRequest("걷는 순간"),
            )

            // when
            categoryService.movePhotos(fixture.galleryId, userId, MoveCategoryPhotosRequest(photoIds, firstDetail.id))
            categoryService.movePhotos(fixture.galleryId, userId, MoveCategoryPhotosRequest(photoIds, secondDetail.id))

            // then
            val assigned = assignmentRepository.findById(photoIds.first()).orElseThrow()
            assertThat(assigned.detailFolderId).isEqualTo(secondDetail.id)
            assertThat(assigned.assignedSource).isEqualTo(CategorySource.USER)
            assertThat(assigned.assignedByUserId).isEqualTo(userId)

            // when
            categoryService.movePhotos(fixture.galleryId, userId, MoveCategoryPhotosRequest(photoIds, null))
            categoryService.deleteDetail(fixture.galleryId, first.id, firstDetail.id, userId)
            categoryService.deleteConcept(fixture.galleryId, second.id, userId)

            // then
            assertThat(assignmentRepository.findAllByPhotoIdIn(photoIds)).isEmpty()
            assertThat(photoRepository.findAllById(photoIds)).hasSize(2)
            assertThat(categoryService.list(fixture.galleryId, userId).single().details).isEmpty()
        }

        @Test
        fun `다른 갤러리 사진과 목적지 폴더는 허용하지 않는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val other = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, 1)
            val otherPhotos = photoFixture.업로드된_사진(other.galleryId, 1)
            val concept = categoryService.createConcept(other.galleryId, other.member.requiredId, CreateConceptFolderRequest("다른 갤러리"))
            val detail = categoryService.createDetail(other.galleryId, concept.id, other.member.requiredId, CreateDetailFolderRequest("다른 사진"))

            // when & then
            assertThatThrownBy {
                categoryService.movePhotos(fixture.galleryId, fixture.member.requiredId, MoveCategoryPhotosRequest(otherPhotos, null))
            }.isInstanceOf(CategoryException::class.java).extracting("errorCode").isEqualTo(CategoryErrorCode.PHOTO_NOT_FOUND)
            assertThatThrownBy {
                categoryService.movePhotos(fixture.galleryId, fixture.member.requiredId, MoveCategoryPhotosRequest(photoIds, detail.id))
            }.isInstanceOf(CategoryException::class.java).extracting("errorCode").isEqualTo(CategoryErrorCode.DETAIL_NOT_FOUND)
            assertThat(assignmentRepository.findAllByPhotoIdIn(photoIds + otherPhotos)).isEmpty()
        }
    }

    @Nested
    @DisplayName("고객 카테고리 변경을 잠글 때")
    inner class AccessBoundary {
        @Test
        fun `비회원과 다른 갤러리 고객은 모든 변경을 할 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val other = galleryFixture.멤버와_열린_갤러리()
            val stranger = userFixture.사용자()

            // when & then
            listOf(stranger.requiredId, other.member.requiredId).forEach { userId ->
                assertAllMutationsDenied(fixture.galleryId, userId, GalleryException::class.java, GalleryErrorCode.GALLERY_ACCESS_DENIED)
            }
        }

        @ParameterizedTest
        @EnumSource(value = GalleryStatus::class, names = ["DRAFT", "CLOSED"])
        fun `열리지 않은 갤러리 고객은 모든 변경을 할 수 없다`(status: GalleryStatus) {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val gallery = galleryRepository.findById(fixture.galleryId).orElseThrow()
            gallery.status = status
            galleryRepository.saveAndFlush(gallery)

            // when & then
            assertAllMutationsDenied(fixture.galleryId, fixture.member.requiredId, GalleryException::class.java, GalleryErrorCode.GALLERY_NOT_OPEN)
        }

        @Test
        fun `마감이 지난 고객은 모든 변경을 할 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            galleryFixture.마감_지남(fixture.galleryId)

            // when & then
            assertAllMutationsDenied(fixture.galleryId, fixture.member.requiredId, GalleryException::class.java, GalleryErrorCode.SELECTION_DEADLINE_PASSED)
        }

        @Test
        fun `제출한 고객은 변경할 수 없지만 작가는 기존 관리 권한을 유지한다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, 1)
            selectionService.select(fixture.galleryId, fixture.member.requiredId, SelectPhotosRequest(photoIds = photoIds))
            selectionService.submit(fixture.galleryId, fixture.member.requiredId)

            // when & then
            assertAllMutationsDenied(fixture.galleryId, fixture.member.requiredId, SelectionException::class.java, SelectionErrorCode.SELECTION_ALREADY_SUBMITTED)
            val created = categoryService.createConcept(fixture.galleryId, fixture.photographer.requiredId, CreateConceptFolderRequest("작가 정리"))
            assertThat(created.name).isEqualTo("작가 정리")
        }

        @Test
        fun `진행 중인 제출 잠금을 기다린 고객 이동은 제출 커밋 후 거절된다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, 1)
            val concept = categoryService.createConcept(fixture.galleryId, fixture.member.requiredId, CreateConceptFolderRequest("컨셉"))
            val detail = categoryService.createDetail(fixture.galleryId, concept.id, fixture.member.requiredId, CreateDetailFolderRequest("세부"))
            selectionService.select(fixture.galleryId, fixture.member.requiredId, SelectPhotosRequest(photoIds = photoIds))
            val submitted = CountDownLatch(1)
            val releaseCommit = CountDownLatch(1)
            val moveStarted = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val submission = executor.submit {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        galleryRepository.requireWithLockById(fixture.galleryId)
                        selectionService.submit(fixture.galleryId, fixture.member.requiredId)
                        submitted.countDown()
                        check(releaseCommit.await(10, TimeUnit.SECONDS))
                    }
                }
                check(submitted.await(10, TimeUnit.SECONDS))

                // when
                val move = executor.submit<Throwable?> {
                    moveStarted.countDown()
                    runCatching {
                        categoryService.movePhotos(fixture.galleryId, fixture.member.requiredId, MoveCategoryPhotosRequest(photoIds, detail.id))
                    }.exceptionOrNull()
                }
                check(moveStarted.await(10, TimeUnit.SECONDS))

                // then
                assertThatThrownBy { move.get(200, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
                releaseCommit.countDown()
                submission.get(10, TimeUnit.SECONDS)
                assertThat(move.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(SelectionException::class.java)
                    .extracting("errorCode").isEqualTo(SelectionErrorCode.SELECTION_ALREADY_SUBMITTED)
                assertThat(assignmentRepository.findAllByPhotoIdIn(photoIds)).isEmpty()
            } finally {
                releaseCommit.countDown()
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    private fun assertAllMutationsDenied(galleryId: Long, userId: Long, type: Class<out Throwable>, errorCode: Any) {
        val mutations: List<() -> Any?> = listOf(
            { categoryService.createConcept(galleryId, userId, CreateConceptFolderRequest("금지")) },
            { categoryService.createDetail(galleryId, -1L, userId, CreateDetailFolderRequest("금지")) },
            { categoryService.movePhotos(galleryId, userId, MoveCategoryPhotosRequest(listOf(-1L), null)) },
            { categoryService.deleteDetail(galleryId, -1L, -1L, userId) },
            { categoryService.deleteConcept(galleryId, -1L, userId) },
        )
        mutations.forEach { mutation ->
            assertThatThrownBy { mutation() }.isInstanceOf(type).extracting("errorCode").isEqualTo(errorCode)
        }
    }
}

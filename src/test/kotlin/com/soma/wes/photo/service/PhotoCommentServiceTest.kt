package com.soma.wes.photo.service

import com.soma.wes.category.dto.request.CreateConceptFolderRequest
import com.soma.wes.category.dto.request.CreateDetailFolderRequest
import com.soma.wes.category.dto.request.MoveCategoryPhotosRequest
import com.soma.wes.category.service.CategoryService
import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.service.CollabGuestQueryService
import com.soma.wes.collab.service.CollabGuestService
import com.soma.wes.collab.service.CollabSessionService
import com.soma.wes.gallery.domain.GalleryInviteKind
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.dto.request.CreateGalleryRequest
import com.soma.wes.gallery.dto.request.IssueGalleryInviteRequest
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.gallery.repository.GalleryInviteRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.service.GalleryInviteService
import com.soma.wes.gallery.service.GalleryService
import com.soma.wes.photo.dto.request.DeletePhotosRequest
import com.soma.wes.photo.dto.request.WritePhotoCommentRequest
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoCommentRepository
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.selection.fixture.SelectionFixture
import com.soma.wes.selection.service.PhotoSelectionService
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.support.IntegrationTest
import com.soma.wes.trash.dto.request.RestorePhotosRequest
import com.soma.wes.trash.service.TrashService
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.ZonedDateTime

/** 부부의 사진 대화는 스튜디오 관리 및 링크로 공개되는 하객 반응과 별개의 권한을 가진다. */
@IntegrationTest
class PhotoCommentServiceTest @Autowired constructor(
    private val photoCommentService: PhotoCommentService,
    private val photoCommentRepository: PhotoCommentRepository,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val userFixture: UserFixture,
    private val selectionFixture: SelectionFixture,
    private val galleryService: GalleryService,
    private val galleryInviteService: GalleryInviteService,
    private val galleryInviteRepository: GalleryInviteRepository,
    private val galleryRepository: GalleryRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val studioRepository: StudioRepository,
    private val photoRepository: PhotoRepository,
    private val photoService: PhotoService,
    private val photoSelectionService: PhotoSelectionService,
    private val trashService: TrashService,
    private val categoryService: CategoryService,
    private val collabSessionService: CollabSessionService,
    private val collabGuestService: CollabGuestService,
    private val collabGuestQueryService: CollabGuestQueryService,
    private val jdbcTemplate: JdbcTemplate,
) {

    private lateinit var fixture: OpenGallery
    private var photoId: Long = 0

    @BeforeEach
    fun setUpBaseData() {
        fixture = galleryFixture.멤버와_열린_갤러리()
        photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).single()
    }

    @Nested
    @DisplayName("부부가 댓글을 공유할 때")
    inner class Share {

        @Test
        fun `실제 초대를 수락한 배우자는 서로의 댓글과 작성자 표시를 함께 본다`() {
            // given
            val spouse = userFixture.사용자(nickname = "배우자")
            val invite = galleryInviteService.issue(fixture.galleryId, fixture.photographer.requiredId)
            val token = galleryInviteRepository.findById(invite.id).orElseThrow().token
            galleryInviteService.accept(token, spouse.requiredId)

            // when
            val first = photoCommentService.write(
                fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("이 사진 어때?"),
            )
            val second = photoCommentService.write(
                fixture.galleryId, photoId, spouse.requiredId, WritePhotoCommentRequest("둘 다 잘 나왔어"),
            )
            val memberView = photoCommentService.list(fixture.galleryId, photoId, fixture.member.requiredId, 0, 20)
            val spouseView = photoCommentService.list(fixture.galleryId, photoId, spouse.requiredId, 0, 20)

            // then
            assertSoftly { softly ->
                softly.assertThat(memberView.contents.map { it.commentId })
                    .containsExactly(first.commentId, second.commentId)
                softly.assertThat(spouseView.contents.map { it.commentId })
                    .containsExactly(first.commentId, second.commentId)
                softly.assertThat(memberView.contents.map { it.mine }).containsExactly(true, false)
                softly.assertThat(spouseView.contents.map { it.mine }).containsExactly(false, true)
                softly.assertThat(memberView.contents.last().authorId).isEqualTo(spouse.requiredId)
                softly.assertThat(memberView.contents.last().nickname).isEqualTo("배우자")
                softly.assertThat(first.photoId).isEqualTo(photoId)
                softly.assertThat(first.authorId).isEqualTo(fixture.member.requiredId)
                softly.assertThat(first.nickname).isEqualTo(fixture.member.nickname)
                softly.assertThat(first.createdAt).isNotNull()
                softly.assertThat(first.mine).isTrue()
            }
        }

        @Test
        fun `개인 작업공간 주인은 PERSONAL_PARTNER 초대를 수락한 배우자와 댓글을 공유한다`() {
            // given
            val owner = userFixture.사용자(nickname = "개인 갤러리 주인")
            val workspace = checkNotNull(workspaceRepository.findByPersonalOwnerUserId(owner.requiredId))
            val gallery = galleryService.create(owner.requiredId, CreateGalleryRequest(workspace.requiredId, "우리 사진"))
            galleryService.open(gallery.id, owner.requiredId)
            val ownPhoto = photoFixture.업로드된_사진(gallery.id, count = 1).single()
            val spouse = userFixture.사용자(nickname = "초대받은 배우자")
            val invite = galleryInviteService.issue(
                gallery.id, owner.requiredId, IssueGalleryInviteRequest(kind = GalleryInviteKind.PERSONAL_PARTNER),
            )
            galleryInviteService.accept(galleryInviteRepository.findById(invite.id).orElseThrow().token, spouse.requiredId)

            // when
            val ownerComment = photoCommentService.write(
                gallery.id, ownPhoto, owner.requiredId, WritePhotoCommentRequest("개인 갤러리 대화"),
            )
            val spouseComment = photoCommentService.write(
                gallery.id, ownPhoto, spouse.requiredId, WritePhotoCommentRequest("함께 고르자"),
            )
            val response = photoCommentService.list(gallery.id, ownPhoto, owner.requiredId, 0, 20)
            photoCommentService.delete(gallery.id, ownPhoto, ownerComment.commentId, owner.requiredId)

            // then
            assertSoftly { softly ->
                softly.assertThat(response.contents.map { it.commentId })
                    .containsExactly(ownerComment.commentId, spouseComment.commentId)
                softly.assertThat(response.contents.map { it.mine }).containsExactly(true, false)
                softly.assertThat(photoCommentService.list(gallery.id, ownPhoto, spouse.requiredId, 0, 20).contents.map { it.commentId })
                    .containsExactly(spouseComment.commentId)
            }
            photoCommentService.delete(gallery.id, ownPhoto, spouseComment.commentId, spouse.requiredId)
            assertThat(photoCommentService.list(gallery.id, ownPhoto, owner.requiredId, 0, 20).contents).isEmpty()
        }

        @Test
        fun `작성 순서로 페이지를 나누고 전체 개수와 다음 페이지를 돌려준다`() {
            // given
            val comments = (1..3).map { number ->
                photoCommentService.write(
                    fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("의견 $number"),
                )
            }

            // when
            val first = photoCommentService.list(fixture.galleryId, photoId, fixture.member.requiredId, 0, 2)
            val second = photoCommentService.list(fixture.galleryId, photoId, fixture.member.requiredId, 1, 2)

            // then
            assertSoftly { softly ->
                softly.assertThat(first.page).isZero()
                softly.assertThat(first.size).isEqualTo(2)
                softly.assertThat(first.totalCount).isEqualTo(3L)
                softly.assertThat(first.hasNext).isTrue()
                softly.assertThat(first.contents.map { it.commentId })
                    .containsExactly(comments[0].commentId, comments[1].commentId)
                softly.assertThat(second.page).isEqualTo(1)
                softly.assertThat(second.totalCount).isEqualTo(3L)
                softly.assertThat(second.hasNext).isFalse()
                softly.assertThat(second.contents.map { it.commentId }).containsExactly(comments[2].commentId)
            }
        }

        @Test
        fun `댓글이 없는 사진은 빈 페이지를 반환한다`() {
            // when
            val response = photoCommentService.list(fixture.galleryId, photoId, fixture.member.requiredId, 0, 20)

            // then
            assertSoftly { softly ->
                softly.assertThat(response.contents).isEmpty()
                softly.assertThat(response.totalCount).isZero()
                softly.assertThat(response.hasNext).isFalse()
            }
        }
    }

    @Nested
    @DisplayName("내부 댓글의 접근을 확인할 때")
    inner class Access {

        @Test
        fun `스튜디오 관리자와 무관한 사용자는 조회 작성 삭제를 할 수 없다`() {
            // given
            val stranger = userFixture.사용자()
            val comment = photoCommentService.write(
                fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("부부만 보는 대화"),
            )

            // when & then
            listOf(fixture.photographer.requiredId, stranger.requiredId).forEach { userId ->
                assertGalleryDenied { photoCommentService.list(fixture.galleryId, photoId, userId, 0, 20) }
                assertGalleryDenied {
                    photoCommentService.write(fixture.galleryId, photoId, userId, WritePhotoCommentRequest("접근 시도"))
                }
                assertGalleryDenied { photoCommentService.delete(fixture.galleryId, photoId, comment.commentId, userId) }
            }
            assertThat(photoCommentRepository.count()).isEqualTo(1L)
        }

        @Test
        fun `스튜디오 작업공간의 일반 구성원도 부부 댓글에 접근할 수 없다`() {
            // given
            val gallery = galleryRepository.findById(fixture.galleryId).orElseThrow()
            val other = userFixture.사용자()
            workspaceMemberRepository.save(WorkspaceMember(gallery.workspaceId, other.requiredId, WorkspaceRole.MEMBER))

            // when & then
            assertGalleryDenied { photoCommentService.list(fixture.galleryId, photoId, other.requiredId, 0, 20) }
            assertGalleryDenied {
                photoCommentService.write(fixture.galleryId, photoId, other.requiredId, WritePhotoCommentRequest("접근 시도"))
            }
        }

        @ParameterizedTest
        @EnumSource(WorkspaceRole::class)
        fun `운영 정지된 스튜디오 구성원이 고객 초대도 수락했어도 내부 댓글은 볼 수 없다`(role: WorkspaceRole) {
            // given
            val gallery = galleryRepository.findById(fixture.galleryId).orElseThrow()
            val actor = if (role == WorkspaceRole.OWNER) {
                fixture.photographer
            } else {
                userFixture.사용자().also { user ->
                    workspaceMemberRepository.save(WorkspaceMember(gallery.workspaceId, user.requiredId, role))
                }
            }
            val invite = galleryInviteService.issue(fixture.galleryId, fixture.photographer.requiredId)
            val token = galleryInviteRepository.findById(invite.id).orElseThrow().token
            val existing = photoCommentService.write(
                fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("부부의 대화"),
            )
            val studio = studioRepository.findById(gallery.workspaceId).orElseThrow()
            studio.suspendedAt = ZonedDateTime.now()
            studioRepository.saveAndFlush(studio)
            // 운영 정지는 기존 관리 권한을 닫지만, 구성원 신분까지 부부로 바꾸지는 않는다.
            galleryInviteService.accept(token, actor.requiredId)

            // when & then
            assertGalleryDenied { photoCommentService.list(fixture.galleryId, photoId, actor.requiredId, 0, 20) }
            assertGalleryDenied {
                photoCommentService.write(
                    fixture.galleryId, photoId, actor.requiredId, WritePhotoCommentRequest("고객 권한으로 접근"),
                )
            }
            assertGalleryDenied {
                photoCommentService.delete(fixture.galleryId, photoId, existing.commentId, actor.requiredId)
            }
        }

        @Test
        fun `개인 주인의 작업공간 구성원 자격이 삭제되면 댓글 접근도 닫힌다`() {
            // given
            val owner = userFixture.사용자()
            val workspace = checkNotNull(workspaceRepository.findByPersonalOwnerUserId(owner.requiredId))
            val gallery = galleryService.create(owner.requiredId, CreateGalleryRequest(workspace.requiredId, "개인 사진"))
            galleryService.open(gallery.id, owner.requiredId)
            val ownPhoto = photoFixture.업로드된_사진(gallery.id, count = 1).single()
            val comment = photoCommentService.write(
                gallery.id, ownPhoto, owner.requiredId, WritePhotoCommentRequest("접근 종료 전 대화"),
            )
            val membership = checkNotNull(
                workspaceMemberRepository.findByWorkspaceIdAndUserId(workspace.requiredId, owner.requiredId),
            )
            membership.deletedAt = ZonedDateTime.now()
            workspaceMemberRepository.saveAndFlush(membership)

            // when & then
            assertGalleryDenied { photoCommentService.list(gallery.id, ownPhoto, owner.requiredId, 0, 20) }
            assertGalleryDenied {
                photoCommentService.write(gallery.id, ownPhoto, owner.requiredId, WritePhotoCommentRequest("접근 시도"))
            }
            assertGalleryDenied { photoCommentService.delete(gallery.id, ownPhoto, comment.commentId, owner.requiredId) }
        }

        @Test
        fun `다른 갤러리 사진을 현재 갤러리 주소에 끼워 넣을 수 없다`() {
            // given
            val other = galleryFixture.멤버와_열린_갤러리()
            val otherPhoto = photoFixture.업로드된_사진(other.galleryId, count = 1).single()
            val comment = photoCommentService.write(
                other.galleryId, otherPhoto, other.member.requiredId, WritePhotoCommentRequest("다른 갤러리 대화"),
            )

            // when & then
            assertPhotoNotFound {
                photoCommentService.list(fixture.galleryId, otherPhoto, fixture.member.requiredId, 0, 20)
            }
            assertPhotoNotFound {
                photoCommentService.write(
                    fixture.galleryId, otherPhoto, fixture.member.requiredId, WritePhotoCommentRequest("접근 시도"),
                )
            }
            assertPhotoNotFound {
                photoCommentService.delete(fixture.galleryId, otherPhoto, comment.commentId, fixture.member.requiredId)
            }
        }

        @Test
        fun `같은 갤러리의 다른 사진 댓글도 현재 사진 주소에서 삭제할 수 없다`() {
            // given
            val otherPhoto = photoFixture.업로드된_사진(fixture.galleryId, count = 1).single()
            val comment = photoCommentService.write(
                fixture.galleryId, otherPhoto, fixture.member.requiredId, WritePhotoCommentRequest("다른 사진의 대화"),
            )

            // when & then
            assertThatThrownBy {
                photoCommentService.delete(fixture.galleryId, photoId, comment.commentId, fixture.member.requiredId)
            }.isInstanceOf(PhotoException::class.java)
                .extracting("errorCode")
                .isEqualTo(PhotoErrorCode.COMMENT_NOT_FOUND)
            assertThat(photoCommentRepository.existsById(comment.commentId)).isTrue()
        }

        @Test
        fun `다른 갤러리 댓글 번호는 현재 사진에 대한 본인 권한으로 삭제할 수 없다`() {
            // given
            val other = galleryFixture.멤버와_열린_갤러리()
            val otherPhoto = photoFixture.업로드된_사진(other.galleryId, count = 1).single()
            val comment = photoCommentService.write(
                other.galleryId, otherPhoto, other.member.requiredId, WritePhotoCommentRequest("다른 부부의 대화"),
            )

            // when & then
            assertThatThrownBy {
                photoCommentService.delete(fixture.galleryId, photoId, comment.commentId, fixture.member.requiredId)
            }.isInstanceOf(PhotoException::class.java)
                .extracting("errorCode")
                .isEqualTo(PhotoErrorCode.COMMENT_NOT_FOUND)
        }

        @Test
        fun `업로드 완료 전 사진에는 댓글을 조회하거나 남기거나 삭제할 수 없다`() {
            // given
            val pendingPhoto = photoFixture.대기중_사진(fixture.galleryId, count = 1).single()

            // when & then
            assertPhotoNotFound {
                photoCommentService.list(fixture.galleryId, pendingPhoto, fixture.member.requiredId, 0, 20)
            }
            assertPhotoNotFound {
                photoCommentService.write(
                    fixture.galleryId, pendingPhoto, fixture.member.requiredId, WritePhotoCommentRequest("먼저 쓰기"),
                )
            }
            assertPhotoNotFound {
                photoCommentService.delete(fixture.galleryId, pendingPhoto, Long.MAX_VALUE, fixture.member.requiredId)
            }
        }
    }

    @Nested
    @DisplayName("댓글 내용을 검증할 때")
    inner class Content {

        @ParameterizedTest
        @ValueSource(strings = ["", " ", "\t\n "])
        fun `빈 내용과 공백만 있는 내용은 거절한다`(content: String) {
            // when & then
            assertInvalidComment(content)
            assertThat(photoCommentRepository.count()).isZero()
        }

        @Test
        fun `원문이 500자를 넘으면 앞뒤 공백을 제거해도 거절한다`() {
            // when & then
            assertInvalidComment("가".repeat(501))
            assertInvalidComment(" " + "가".repeat(500))
            assertThat(photoCommentRepository.count()).isZero()
        }

        @Test
        fun `500자는 허용하고 앞뒤 공백만 제거한다`() {
            // when
            val boundary = photoCommentService.write(
                fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("가".repeat(500)),
            )
            val trimmed = photoCommentService.write(
                fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("  사진 안의\n두 사람  "),
            )

            // then
            assertThat(boundary.content).hasSize(500)
            assertThat(trimmed.content).isEqualTo("사진 안의\n두 사람")
        }
    }

    @Nested
    @DisplayName("댓글을 삭제할 때")
    inner class Delete {

        @Test
        fun `작성자만 삭제하며 삭제한 댓글은 양쪽 목록에서 사라진다`() {
            // given
            val spouse = galleryFixture.멤버(fixture.galleryId)
            val comment = photoCommentService.write(
                fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("지울 의견"),
            )

            // when & then
            assertThatThrownBy {
                photoCommentService.delete(fixture.galleryId, photoId, comment.commentId, spouse.requiredId)
            }.isInstanceOf(PhotoException::class.java)
                .extracting("errorCode")
                .isEqualTo(PhotoErrorCode.COMMENT_DELETE_DENIED)

            photoCommentService.delete(fixture.galleryId, photoId, comment.commentId, fixture.member.requiredId)

            assertSoftly { softly ->
                softly.assertThat(photoCommentService.list(fixture.galleryId, photoId, fixture.member.requiredId, 0, 20).contents)
                    .isEmpty()
                softly.assertThat(photoCommentService.list(fixture.galleryId, photoId, spouse.requiredId, 0, 20).contents)
                    .isEmpty()
                softly.assertThat(photoCommentRepository.existsById(comment.commentId)).isFalse()
                softly.assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from photo_comments where id = ? and deleted_at is not null",
                    Long::class.java,
                    comment.commentId,
                )).isEqualTo(1L)
            }
            assertThatThrownBy {
                photoCommentService.delete(fixture.galleryId, photoId, comment.commentId, fixture.member.requiredId)
            }.isInstanceOf(PhotoException::class.java)
                .extracting("errorCode")
                .isEqualTo(PhotoErrorCode.COMMENT_NOT_FOUND)
        }
    }

    @Nested
    @DisplayName("갤러리 진행 상태가 바뀔 때")
    inner class State {

        @Test
        fun `닫힌 갤러리는 조회와 본인 삭제를 허용하고 작성은 막는다`() {
            // given
            val comment = photoCommentService.write(
                fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("닫기 전 대화"),
            )
            galleryService.close(fixture.galleryId, fixture.photographer.requiredId)

            // when & then
            assertThat(photoCommentService.list(fixture.galleryId, photoId, fixture.member.requiredId, 0, 20).contents.map { it.commentId })
                .containsExactly(comment.commentId)
            assertThatThrownBy {
                photoCommentService.write(
                    fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("닫힌 뒤 대화"),
                )
            }.isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_NOT_OPEN)
            photoCommentService.delete(fixture.galleryId, photoId, comment.commentId, fixture.member.requiredId)
            assertThat(photoCommentRepository.count()).isZero()
        }

        @Test
        fun `선택 기한이 지나도 조회와 본인 삭제는 가능하고 작성은 막는다`() {
            // given
            val comment = photoCommentService.write(
                fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("기한 전 대화"),
            )
            galleryFixture.마감_지남(fixture.galleryId)

            // when & then
            assertThat(photoCommentService.list(fixture.galleryId, photoId, fixture.member.requiredId, 0, 20).totalCount)
                .isEqualTo(1L)
            assertThatThrownBy {
                photoCommentService.write(
                    fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("기한 뒤 대화"),
                )
            }.isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.SELECTION_DEADLINE_PASSED)
            photoCommentService.delete(fixture.galleryId, photoId, comment.commentId, fixture.member.requiredId)
            assertThat(photoCommentRepository.count()).isZero()
        }

        @Test
        fun `선택을 제출해도 열린 갤러리에서는 댓글을 계속 쓴다`() {
            // given
            selectionFixture.담긴_사진(selectionFixture.셀렉(fixture.galleryId), listOf(photoId))
            photoSelectionService.submit(fixture.galleryId, fixture.member.requiredId)

            // when
            val comment = photoCommentService.write(
                fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("제출 후 의견"),
            )

            // then
            assertThat(photoCommentService.list(fixture.galleryId, photoId, fixture.member.requiredId, 0, 20).contents.map { it.commentId })
                .containsExactly(comment.commentId)
            photoCommentService.delete(fixture.galleryId, photoId, comment.commentId, fixture.member.requiredId)
            assertThat(photoCommentRepository.count()).isZero()
        }

        @Test
        fun `DRAFT 갤러리의 댓글은 초대 멤버에게 노출되지 않는다`() {
            // given
            val comment = photoCommentService.write(
                fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("이전 대화"),
            )
            val gallery = galleryRepository.findById(fixture.galleryId).orElseThrow()
            gallery.status = GalleryStatus.DRAFT
            galleryRepository.saveAndFlush(gallery)

            // when & then
            assertGalleryDenied { photoCommentService.list(fixture.galleryId, photoId, fixture.member.requiredId, 0, 20) }
            assertGalleryDenied {
                photoCommentService.delete(fixture.galleryId, photoId, comment.commentId, fixture.member.requiredId)
            }
            assertGalleryDenied {
                photoCommentService.write(
                    fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("숨은 대화"),
                )
            }
        }
    }

    @Nested
    @DisplayName("사진과 갤러리를 휴지통으로 보낼 때")
    inner class Trash {

        @Test
        fun `사진을 숨기는 동안 댓글 접근을 막고 복원하면 대화를 보존한다`() {
            // given
            val comment = photoCommentService.write(
                fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("사진과 함께 보존"),
            )
            photoService.moveToTrash(fixture.galleryId, fixture.photographer.requiredId, DeletePhotosRequest(listOf(photoId)))

            // when & then
            assertPhotoNotFound { photoCommentService.list(fixture.galleryId, photoId, fixture.member.requiredId, 0, 20) }
            assertPhotoNotFound {
                photoCommentService.write(
                    fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("숨은 사진"),
                )
            }
            assertPhotoNotFound {
                photoCommentService.delete(fixture.galleryId, photoId, comment.commentId, fixture.member.requiredId)
            }

            trashService.restorePhotos(fixture.galleryId, fixture.photographer.requiredId, RestorePhotosRequest(listOf(photoId)))

            assertThat(photoCommentService.list(fixture.galleryId, photoId, fixture.member.requiredId, 0, 20).contents.map { it.commentId })
                .containsExactly(comment.commentId)
        }

        @Test
        fun `갤러리를 숨기는 동안 댓글 접근을 막고 복원하면 대화를 보존한다`() {
            // given
            val comment = photoCommentService.write(
                fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("갤러리와 함께 보존"),
            )
            galleryService.moveToTrash(fixture.galleryId, fixture.photographer.requiredId)

            // when & then
            val operations = listOf<() -> Unit>(
                { photoCommentService.list(fixture.galleryId, photoId, fixture.member.requiredId, 0, 20) },
                {
                    photoCommentService.write(
                        fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("숨은 갤러리"),
                    )
                },
                { photoCommentService.delete(fixture.galleryId, photoId, comment.commentId, fixture.member.requiredId) },
            )
            operations.forEach { operation ->
                assertThatThrownBy { operation() }.isInstanceOf(GalleryException::class.java)
                    .extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_NOT_FOUND)
            }

            trashService.restoreGallery(fixture.galleryId, fixture.photographer.requiredId)

            assertThat(photoCommentService.list(fixture.galleryId, photoId, fixture.member.requiredId, 0, 20).contents.map { it.commentId })
                .containsExactly(comment.commentId)
        }

        @Test
        fun `사진을 물리 삭제하면 숨긴 댓글을 포함해 고아 댓글을 남기지 않는다`() {
            // given
            val removed = photoCommentService.write(
                fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("먼저 삭제한 댓글"),
            )
            photoCommentService.delete(fixture.galleryId, photoId, removed.commentId, fixture.member.requiredId)
            photoCommentService.write(
                fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("아직 남은 댓글"),
            )

            // when
            // 물리 삭제 배치의 DB 경계만 재현한다. 테스트에서 실제 S3 삭제는 실행하지 않는다.
            photoRepository.deleteById(photoId)

            // then
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from photo_comments where photo_id = ?", Long::class.java, photoId,
            )).isZero()
            assertPhotoNotFound { photoCommentService.list(fixture.galleryId, photoId, fixture.member.requiredId, 0, 20) }
        }
    }

    @Nested
    @DisplayName("하객과 같은 사진을 볼 때")
    inner class GuestSeparation {

        @Test
        fun `내부 댓글과 링크에 공개한 하객 댓글은 서로의 목록에 섞이지 않는다`() {
            // given
            val concept = categoryService.createConcept(
                fixture.galleryId, fixture.photographer.requiredId, CreateConceptFolderRequest("공유할 사진"),
            )
            val detail = categoryService.createDetail(
                fixture.galleryId, concept.id, fixture.photographer.requiredId, CreateDetailFolderRequest("후보"),
            )
            categoryService.movePhotos(
                fixture.galleryId, fixture.photographer.requiredId, MoveCategoryPhotosRequest(listOf(photoId), detail.id),
            )
            val session = collabSessionService.open(
                fixture.galleryId, fixture.photographer.requiredId, OpenCollabSessionRequest(concept.id, "하객 의견"),
            )
            val token = session.collabUrl.substringAfterLast('/')
            val guest = collabGuestService.enter(token, EnterCollabRequest("친구"))

            // when
            photoCommentService.write(
                fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest("부부끼리만 보는 의견"),
            )
            collabGuestService.writeComment(
                token, photoId, guest.guestToken, WriteCollabCommentRequest("하객이 남긴 의견"),
            )
            val internal = photoCommentService.list(fixture.galleryId, photoId, fixture.member.requiredId, 0, 20)
            val guestComments = collabGuestQueryService.listComments(token, photoId, guest.guestToken, 0, 20)

            // then
            assertThat(internal.contents.map { it.content }).containsExactly("부부끼리만 보는 의견")
            assertThat(guestComments.contents.map { it.content }).containsExactly("하객이 남긴 의견")
        }
    }

    private fun assertGalleryDenied(operation: () -> Unit) {
        assertThatThrownBy { operation() }.isInstanceOf(GalleryException::class.java)
            .extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
    }

    private fun assertPhotoNotFound(operation: () -> Unit) {
        assertThatThrownBy { operation() }.isInstanceOf(PhotoException::class.java)
            .extracting("errorCode").isEqualTo(PhotoErrorCode.PHOTO_NOT_FOUND)
    }

    private fun assertInvalidComment(content: String) {
        assertThatThrownBy {
            photoCommentService.write(fixture.galleryId, photoId, fixture.member.requiredId, WritePhotoCommentRequest(content))
        }.isInstanceOf(PhotoException::class.java)
            .extracting("errorCode").isEqualTo(PhotoErrorCode.INVALID_COMMENT)
    }
}

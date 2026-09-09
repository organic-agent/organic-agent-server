package com.soma.wes.activity.service

import com.soma.wes.activity.repository.ActivityRepository
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.dto.request.CompleteUploadRequest
import com.soma.wes.photo.dto.request.IssueUploadUrlsRequest
import com.soma.wes.photo.dto.request.WritePhotoCommentRequest
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.service.PhotoCommentService
import com.soma.wes.photo.service.PhotoService
import com.soma.wes.selection.dto.request.SelectPhotosRequest
import com.soma.wes.selection.service.PhotoSelectionService
import com.soma.wes.studio.service.StudioInviteService
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.dto.response.UserWorkspaceKind
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.user.service.UserService
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@IntegrationTest
class ActivityRecorderTest @Autowired constructor(
    private val activity: ActivityRepository,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val userFixture: UserFixture,
    private val photoService: PhotoService,
    private val selectionService: PhotoSelectionService,
    private val comments: PhotoCommentService,
    private val studioInvites: StudioInviteService,
    private val users: UserService,
    private val galleries: GalleryRepository,
    private val galleryMembers: GalleryMemberRepository,
    private val workspaceMembers: WorkspaceMemberRepository,
    private val jdbc: JdbcTemplate,
    private val transactions: PlatformTransactionManager,
    private val collabFixture: com.soma.wes.collab.fixture.CollabFixture,
    private val guests: com.soma.wes.collab.service.CollabGuestService,
    private val retouch: com.soma.wes.retouch.service.RetouchService,
) {
    @Nested
    @DisplayName("성공한 업무 활동을 기록할 때")
    inner class Record {
        @Test
        fun `업로드 완료는 해당 갤러리와 작업공간 시각만 바꾸며 도메인 버전은 유지한다`() {
            // given
            val a = galleryFixture.멤버와_열린_갤러리()
            val b = galleryFixture.멤버와_열린_갤러리()
            val workspaceId = galleries.findById(a.galleryId).orElseThrow().workspaceId
            val otherWorkspaceId = galleries.findById(b.galleryId).orElseThrow().workspaceId
            val ids = issueUploadUrls(a)
            clearActivity(a)
            val galleryVersion = version("galleries", a.galleryId)
            val studioVersion = version("studios", workspaceId)

            // when
            photoService.completeUpload(a.galleryId, a.photographer.requiredId, CompleteUploadRequest(ids))

            // then
            assertThat(activity.findGalleryActivity(listOf(a.galleryId, b.galleryId)).keys).containsExactly(a.galleryId)
            assertThat(activity.findWorkspaceActivity(listOf(workspaceId, otherWorkspaceId)).keys).containsExactly(workspaceId)
            assertThat(version("galleries", a.galleryId)).isEqualTo(galleryVersion)
            assertThat(version("studios", workspaceId)).isEqualTo(studioVersion)
        }

        @Test
        fun `사진을 담고 댓글을 쓰면 최근 활동이 각각 갱신된다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val ids = photoFixture.업로드된_사진(fixture.galleryId, 1)

            // when
            selectionService.select(fixture.galleryId, fixture.member.requiredId, SelectPhotosRequest(ids))
            val selectedAt = activity.findGalleryActivity(listOf(fixture.galleryId)).getValue(fixture.galleryId)
            jdbc.update("update gallery_activity set last_activity_at = timestamp with time zone '2000-01-01 00:00:00Z'")
            comments.write(fixture.galleryId, ids.single(), fixture.member.requiredId, WritePhotoCommentRequest("이 사진이 좋아요"))

            // then
            val commentedAt = activity.findGalleryActivity(listOf(fixture.galleryId)).getValue(fixture.galleryId)
            assertThat(commentedAt.toInstant()).isAfterOrEqualTo(selectedAt.toInstant())
        }

        @Test
        fun `스튜디오 초대 발급은 작업공간 시각만 바꾸고 갤러리 활동으로 기록하지 않는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val workspaceId = galleries.findById(fixture.galleryId).orElseThrow().workspaceId

            // when
            studioInvites.issue(workspaceId, fixture.photographer.requiredId)

            // then
            assertThat(activity.findWorkspaceActivity(listOf(workspaceId))).containsKey(workspaceId)
            assertThat(activity.findGalleryActivity(listOf(fixture.galleryId))).isEmpty()
        }
    }


    @Nested
    @DisplayName("공유와 보정 활동을 기록할 때")
    inner class Feedback {
        @Test
        fun `하객 댓글과 좋아요는 활동을 기록하고 같은 좋아요 재요청은 바꾸지 않는다`() {
            // given
            val shared = collabFixture.사진이_있는_세션()
            val guest = guests.enter(shared.token, com.soma.wes.collab.dto.request.EnterCollabRequest("친구"))
            val old = java.time.Instant.parse("2000-01-01T00:00:00Z")
            jdbc.update("update gallery_activity set last_activity_at = timestamp with time zone '2000-01-01 00:00:00Z'")

            // when
            guests.writeComment(shared.token, shared.photoId, guest.guestToken,
                com.soma.wes.collab.dto.request.WriteCollabCommentRequest("좋아요"))
            assertThat(activity.findGalleryActivity(listOf(shared.galleryId)).getValue(shared.galleryId).toInstant()).isAfter(old)
            jdbc.update("update gallery_activity set last_activity_at = timestamp with time zone '2000-01-01 00:00:00Z'")
            guests.like(shared.token, shared.photoId, guest.guestToken)
            val likedAt = activity.findGalleryActivity(listOf(shared.galleryId)).getValue(shared.galleryId)
            guests.like(shared.token, shared.photoId, guest.guestToken)

            // then
            assertThat(likedAt.toInstant()).isAfter(old)
            assertThat(activity.findGalleryActivity(listOf(shared.galleryId)).getValue(shared.galleryId)).isEqualTo(likedAt)
        }

        @Test
        fun `보정사진 추가와 요청 수정과 제거를 각각 활동으로 기록한다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, 1).single()
            val old = java.time.Instant.parse("2000-01-01T00:00:00Z")

            // when & then
            retouch.addPhotos(fixture.galleryId, fixture.member.requiredId,
                com.soma.wes.retouch.dto.request.AddRetouchPhotosRequest(listOf(photoId)))
            assertThat(activity.findGalleryActivity(listOf(fixture.galleryId))).containsKey(fixture.galleryId)
            jdbc.update("update gallery_activity set last_activity_at = timestamp with time zone '2000-01-01 00:00:00Z'")
            retouch.updatePhoto(fixture.galleryId, photoId, fixture.member.requiredId,
                com.soma.wes.retouch.dto.request.UpdateRetouchPhotoRequest(requestText = "밝게 해주세요"))
            assertThat(activity.findGalleryActivity(listOf(fixture.galleryId)).getValue(fixture.galleryId).toInstant()).isAfter(old)
            jdbc.update("update gallery_activity set last_activity_at = timestamp with time zone '2000-01-01 00:00:00Z'")
            retouch.removePhoto(fixture.galleryId, photoId, fixture.member.requiredId)
            assertThat(activity.findGalleryActivity(listOf(fixture.galleryId)).getValue(fixture.galleryId).toInstant()).isAfter(old)
        }
    }

    @Nested
    @DisplayName("실패와 조회를 처리할 때")
    inner class Unchanged {
        @Test
        fun `조회와 권한 없는 업로드 요청은 활동을 만들지 않는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val ids = issueUploadUrls(fixture)
            clearActivity(fixture)
            val other = userFixture.사용자()

            // when & then
            users.listWorkspaces(fixture.photographer.requiredId)
            selectionService.get(fixture.galleryId, fixture.member.requiredId)
            assertThatThrownBy {
                photoService.completeUpload(fixture.galleryId, other.requiredId, CompleteUploadRequest(ids))
            }.isInstanceOf(GalleryException::class.java).extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
            assertThat(activity.findGalleryActivity(listOf(fixture.galleryId))).isEmpty()
            assertThat(jdbc.queryForObject("select count(*) from workspace_activity", Long::class.java)).isZero()
        }

        @Test
        fun `업로드 트랜잭션을 롤백하면 활동도 함께 없어진다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val ids = issueUploadUrls(fixture)
            clearActivity(fixture)

            // when
            TransactionTemplate(transactions).executeWithoutResult { status ->
                photoService.completeUpload(fixture.galleryId, fixture.photographer.requiredId, CompleteUploadRequest(ids))
                status.setRollbackOnly()
            }

            // then
            assertThat(activity.findGalleryActivity(listOf(fixture.galleryId))).isEmpty()
            assertThat(jdbc.queryForObject("select count(*) from workspace_activity", Long::class.java)).isZero()
            assertThat(jdbc.queryForObject("select status from photos where id = ?", String::class.java, ids.single())).isEqualTo("PENDING")
        }
    }

    @Test
    fun `목록은 사진 활동을 반영해 각 스튜디오와 갤러리 섹션을 최근순으로 정렬한다`() {
        // given
        val a = galleryFixture.멤버와_열린_갤러리()
        val b = galleryFixture.멤버와_열린_갤러리()
        val wa = galleries.findById(a.galleryId).orElseThrow().workspaceId
        val wb = galleries.findById(b.galleryId).orElseThrow().workspaceId
        workspaceMembers.save(WorkspaceMember(workspaceId = wb, userId = a.photographer.requiredId, role = WorkspaceRole.MEMBER))
        galleryMembers.save(GalleryMember(galleryId = b.galleryId, userId = a.member.requiredId))
        for (table in listOf("studios", "galleries", "workspace_members")) {
            jdbc.update("update $table set created_at = now() - interval '3 days', updated_at = now() - interval '3 days'")
        }
        jdbc.update("update studios set updated_at = now() - interval '1 day' where workspace_id = ?", wb)
        jdbc.update("update galleries set updated_at = now() - interval '1 day' where id = ?", b.galleryId)
        val ids = issueUploadUrls(a)
        clearActivity(a)
        assertThat(users.listWorkspaces(a.photographer.requiredId).filter { it.kind == UserWorkspaceKind.STUDIO }.first().workspaceId).isEqualTo(wb)
        assertThat(users.listWorkspaces(a.member.requiredId).first().galleryId).isEqualTo(b.galleryId)

        // when
        photoService.completeUpload(a.galleryId, a.photographer.requiredId, CompleteUploadRequest(ids))

        // then
        assertThat(users.listWorkspaces(a.photographer.requiredId).filter { it.kind == UserWorkspaceKind.STUDIO }.first().workspaceId).isEqualTo(wa)
        assertThat(users.listWorkspaces(a.member.requiredId).first().galleryId).isEqualTo(a.galleryId)
    }

    /** 현재 업로드 계약의 필수 크기·CRC32C를 포함한 PENDING 사진을 준비한다. S3 PUT은 수행하지 않는다. */
    private fun issueUploadUrls(fixture: OpenGallery): List<Long> = photoService.issueUploadUrls(
        fixture.galleryId,
        fixture.photographer.requiredId,
        IssueUploadUrlsRequest(
            listOf(IssueUploadUrlsRequest.FileRequest(
                fileName = "activity.jpg", contentType = "image/jpeg", contentLength = 1024, crc32c = "wdRDgw==",
            )),
        ),
    ).uploads.map { it.photoId }

    /** URL 발급 활동과 분리해 완료 통보/인가 실패/롤백이 활동에 미치는 영향을 검증한다. */
    private fun clearActivity(fixture: OpenGallery) {
        val workspaceId = galleries.findById(fixture.galleryId).orElseThrow().workspaceId
        jdbc.update("DELETE FROM gallery_activity WHERE gallery_id = ?", fixture.galleryId)
        jdbc.update("DELETE FROM workspace_activity WHERE workspace_id = ?", workspaceId)
    }

    private fun version(table: String, id: Long): Long {
        val key = if (table == "studios") "workspace_id" else "id"
        return jdbc.queryForObject("select version from $table where $key = ?", Long::class.java, id)!!
    }
}

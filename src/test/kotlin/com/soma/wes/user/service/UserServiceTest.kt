package com.soma.wes.user.service

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.notification.repository.UserNotificationRepository
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.support.TestcontainersConfiguration
import com.soma.wes.user.domain.Role
import com.soma.wes.user.domain.User
import com.soma.wes.user.exception.UserErrorCode
import com.soma.wes.user.exception.UserException
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.workspace.domain.Workspace
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class UserServiceTest @Autowired constructor(
    private val userService: UserService,
    private val userRepository: UserRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val notificationRepository: UserNotificationRepository,
) {

    @Test
    fun `id로 사용자 정보를 조회한다`() {
        // given
        val user = userRepository.save(
            User(
                provider = OAuthProvider.GOOGLE,
                providerId = "google-me-1",
                nickname = "테스터",
                email = "tester@example.com",
            ),
        )

        // when
        val response = userService.getUser(user.id!!)

        // then
        assertSoftly { softly ->
            softly.assertThat(response.id).isEqualTo(user.id)
            softly.assertThat(response.provider).isEqualTo(OAuthProvider.GOOGLE)
            softly.assertThat(response.nickname).isEqualTo("테스터")
            softly.assertThat(response.email).isEqualTo("tester@example.com")
            softly.assertThat(response.role).isEqualTo(Role.USER)
        }
    }

    @Test
    fun `존재하지 않는 사용자를 조회하면 USER_NOT_FOUND를 던진다`() {
        // when & then
        assertThatThrownBy { userService.getUser(-1L) }
            .isInstanceOf(UserException::class.java)
            .extracting("errorCode")
            .isEqualTo(UserErrorCode.USER_NOT_FOUND)
    }

    @Test
    fun `내 작업공간은 빈 개인 공간을 숨기고 실제 스튜디오와 갤러리만 준다`() {
        val user = saveUser("workspace-owner")
        val personal = workspaceRepository.save(Workspace.personal(user.requiredId, "빈 개인 공간"))
        workspaceMemberRepository.save(WorkspaceMember(personal.requiredId, user.requiredId, WorkspaceRole.OWNER))
        val studioWorkspace = workspaceRepository.save(Workspace.studio("스튜디오"))
        workspaceMemberRepository.save(
            WorkspaceMember(studioWorkspace.requiredId, user.requiredId, WorkspaceRole.OWNER),
        )
        studioRepository.save(Studio(studioWorkspace.requiredId, "스튜디오", "workspace-studio"))
        val personalGallery = galleryRepository.save(
            Gallery(personal.requiredId, user.requiredId, "개인 갤러리"),
        )

        val result = userService.listWorkspaces(user.requiredId)

        assertThat(result.map { it.name }).containsExactlyInAnyOrder("스튜디오", "개인 갤러리")
        assertThat(result.mapNotNull { it.galleryId }).containsExactly(personalGallery.requiredId)
        assertThat(result).noneMatch { it.name == "빈 개인 공간" }
    }

    @Test
    fun `회원 탈퇴는 소유 스튜디오를 지우고 다른 멤버에게 알린다`() {
        val owner = saveUser("delete-owner")
        val member = saveUser("delete-member")
        val workspace = workspaceRepository.save(Workspace.studio("삭제될 스튜디오"))
        workspaceMemberRepository.save(WorkspaceMember(workspace.requiredId, owner.requiredId, WorkspaceRole.OWNER))
        workspaceMemberRepository.save(WorkspaceMember(workspace.requiredId, member.requiredId, WorkspaceRole.MEMBER))
        studioRepository.save(Studio(workspace.requiredId, "삭제될 스튜디오", "delete-studio"))
        galleryRepository.save(Gallery(workspace.requiredId, owner.requiredId, "삭제될 갤러리"))

        userService.delete(owner.requiredId)

        assertThat(userRepository.findById(owner.requiredId)).isEmpty
        assertThat(workspaceRepository.findById(workspace.requiredId)).isEmpty
        assertThat(notificationRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(member.requiredId))
            .singleElement()
            .extracting("type")
            .isEqualTo(com.soma.wes.notification.domain.UserNotificationType.WORKSPACE_DELETED)
    }

    private fun saveUser(providerId: String): User = userRepository.save(
        User(
            provider = OAuthProvider.GOOGLE,
            providerId = providerId,
            nickname = providerId,
            email = "$providerId@example.com",
        ),
    )
}

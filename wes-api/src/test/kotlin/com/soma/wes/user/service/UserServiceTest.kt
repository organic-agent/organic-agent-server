package com.soma.wes.user.service

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.domain.Role
import com.soma.wes.user.domain.User
import com.soma.wes.user.domain.UserColor
import com.soma.wes.user.dto.request.ChangeUserColorRequest
import com.soma.wes.user.dto.request.UpdateUserRequest
import com.soma.wes.user.exception.UserErrorCode
import com.soma.wes.user.exception.UserException
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.workspace.domain.Workspace
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class UserServiceTest @Autowired constructor(
    private val userService: UserService,
    private val userRepository: UserRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val userFixture: UserFixture,
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
        val user = userFixture.계정만("workspace-owner")
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
    fun `다른 OWNER가 있으면 회원 탈퇴해도 스튜디오와 갤러리는 유지한다`() {
        val owner = userFixture.계정만("delete-owner")
        val otherOwner = userFixture.계정만("remaining-owner")
        val workspace = workspaceRepository.save(Workspace.studio("유지될 스튜디오"))
        workspaceMemberRepository.save(WorkspaceMember(workspace.requiredId, owner.requiredId, WorkspaceRole.OWNER))
        workspaceMemberRepository.save(WorkspaceMember(workspace.requiredId, otherOwner.requiredId, WorkspaceRole.OWNER))
        studioRepository.save(Studio(workspace.requiredId, "유지될 스튜디오", "kept-studio"))
        val gallery = galleryRepository.save(Gallery(workspace.requiredId, owner.requiredId, "유지될 갤러리"))

        userService.delete(owner.requiredId)

        assertThat(userRepository.findById(owner.requiredId)).isEmpty
        assertThat(workspaceRepository.findById(workspace.requiredId)).isPresent
        assertThat(studioRepository.findById(workspace.requiredId)).isPresent
        assertThat(galleryRepository.findById(gallery.requiredId)).isPresent
        assertThat(workspaceMemberRepository.findByWorkspaceIdAndUserId(workspace.requiredId, owner.requiredId)).isNull()
        assertThat(workspaceMemberRepository.findByWorkspaceIdAndUserId(workspace.requiredId, otherOwner.requiredId)).isNotNull
    }

    @Test
    fun `유일한 스튜디오 OWNER가 탈퇴하면 소유 스튜디오를 함께 삭제한다`() {
        val owner = userFixture.계정만("last-owner")
        val workspace = workspaceRepository.save(Workspace.studio("마지막 OWNER 스튜디오"))
        workspaceMemberRepository.save(WorkspaceMember(workspace.requiredId, owner.requiredId, WorkspaceRole.OWNER))
        studioRepository.save(Studio(workspace.requiredId, "마지막 OWNER 스튜디오", "last-owner-studio"))
        val gallery = galleryRepository.save(Gallery(workspace.requiredId, owner.requiredId, "삭제할 갤러리"))

        userService.delete(owner.requiredId)

        assertThat(userRepository.findById(owner.requiredId)).isEmpty
        assertThat(workspaceRepository.findById(workspace.requiredId)).isEmpty
        assertThat(galleryRepository.findById(gallery.requiredId)).isEmpty
    }

    @Test
    fun `닉네임 수정은 공백을 정리하고 빈 이름은 거절한다`() {
        val user = userFixture.계정만("nickname-validation")
        assertThat(userService.update(user.requiredId, com.soma.wes.user.dto.request.UpdateUserRequest(" 새 이름 ")).nickname)
            .isEqualTo("새 이름")
        assertThatThrownBy { userService.update(user.requiredId, com.soma.wes.user.dto.request.UpdateUserRequest("  ")) }
            .isInstanceOf(UserException::class.java).extracting("errorCode").isEqualTo(UserErrorCode.INVALID_NICKNAME)
    }

    @Nested
    @DisplayName("내 색을 바꿀 때")
    inner class ChangeColor {

        @Test
        fun `웹 키로 저장하면 내 정보에 같은 키로 실려 온다`() {
            // 기기마다 따로 두면 다른 기기와 파트너 화면에서 안 보인다 — 계정에서 다시 읽어도 같아야 한다.
            // given
            val user = userFixture.계정만("color-save")

            // when
            val changed = userService.changeColor(user.requiredId, ChangeUserColorRequest(color = "coral"))

            // then
            assertSoftly { softly ->
                softly.assertThat(changed.color).isEqualTo("coral")
                softly.assertThat(userService.getUser(user.requiredId).color).isEqualTo("coral")
                softly.assertThat(userRepository.findById(user.requiredId).orElseThrow().color).isEqualTo(UserColor.CORAL)
            }
        }

        @Test
        fun `null을 보내면 고른 색을 지운다`() {
            // given
            val user = userFixture.계정만("color-clear")
            userService.changeColor(user.requiredId, ChangeUserColorRequest(color = "sky"))

            // when
            val cleared = userService.changeColor(user.requiredId, ChangeUserColorRequest(color = null))

            // then
            assertThat(cleared.color).isNull()
            assertThat(userService.getUser(user.requiredId).color).isNull()
        }

        @Test
        fun `일곱 키 밖이거나 대문자면 거절하고 색을 바꾸지 않는다`() {
            // given
            val user = userFixture.계정만("color-invalid")
            userService.changeColor(user.requiredId, ChangeUserColorRequest(color = "mint"))

            // when & then
            for (key in listOf("purple", "SKY", "")) {
                assertThatThrownBy { userService.changeColor(user.requiredId, ChangeUserColorRequest(color = key)) }
                    .isInstanceOf(UserException::class.java)
                    .extracting("errorCode")
                    .isEqualTo(UserErrorCode.INVALID_COLOR)
            }
            assertThat(userService.getUser(user.requiredId).color).isEqualTo("mint")
        }

        @Test
        fun `닉네임을 고쳐도 색은 그대로다`() {
            // 웹은 닉네임 수정에 nickname만 보낸다. 같은 API였다면 "안 보낸 색"이 지움으로 읽힐 수 있었다.
            // given
            val user = userFixture.계정만("color-nickname")
            userService.changeColor(user.requiredId, ChangeUserColorRequest(color = "rose"))

            // when
            val updated = userService.update(user.requiredId, UpdateUserRequest("새 이름"))

            // then
            assertThat(updated.color).isEqualTo("rose")
        }
    }
}

package com.soma.wes.studio.service

import com.soma.wes.studio.dto.request.CreateStudioRequest
import com.soma.wes.studio.dto.request.UpdateStudioRequest
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.domain.WorkspaceType
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class StudioServiceTest @Autowired constructor(
    private val studioService: StudioService,
    private val studioRepository: StudioRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val userFixture: UserFixture,
) {
    @Test
    fun `스튜디오를 만들면 별도 STUDIO 작업공간과 OWNER 멤버십이 생긴다`() {
        val user = userFixture.사용자()

        val result = studioService.create(
            user.requiredId,
            CreateStudioRequest("오가닉 스튜디오", "  Organic-Studio  ", "인스타그램"),
        )

        val workspace = workspaceRepository.findById(result.id).orElseThrow()
        val membership = workspaceMemberRepository.findByWorkspaceIdAndUserId(result.id, user.requiredId)
        assertThat(result.galleryUrl).isEqualTo("organic-studio")
        assertThat(workspace.type).isEqualTo(WorkspaceType.STUDIO)
        assertThat(membership?.role).isEqualTo(WorkspaceRole.OWNER)
        assertThat(studioRepository.findById(result.id)).isPresent
    }

    @Test
    fun `한 사용자가 여러 스튜디오를 소유할 수 있다`() {
        val user = userFixture.사용자()

        studioService.create(user.requiredId, CreateStudioRequest("첫 번째", "first-studio", null))
        studioService.create(user.requiredId, CreateStudioRequest("두 번째", "second-studio", null))

        assertThat(studioService.listMine(user.requiredId)).hasSize(2)
        assertThatThrownBy { studioService.getMyStudio(user.requiredId) }
            .isInstanceOf(StudioException::class.java)
            .extracting("errorCode")
            .isEqualTo(StudioErrorCode.STUDIO_SELECTION_REQUIRED)
    }

    @Test
    fun `스튜디오 이름을 바꾸면 작업공간 이름도 같이 바뀐다`() {
        val user = userFixture.사용자()
        val studio = studioService.create(user.requiredId, CreateStudioRequest("이전 이름", "before-url", null))

        studioService.update(studio.id, user.requiredId, UpdateStudioRequest("새 이름", "after-url"))

        assertThat(workspaceRepository.findById(studio.id).orElseThrow().name).isEqualTo("새 이름")
        assertThat(studioRepository.findById(studio.id).orElseThrow().name).isEqualTo("새 이름")
    }

    @Test
    fun `이미 쓰는 갤러리 주소는 거절한다`() {
        studioService.create(
            userFixture.사용자().requiredId,
            CreateStudioRequest("먼저 만든 곳", "taken-url", null),
        )

        assertThatThrownBy {
            studioService.create(
                userFixture.사용자().requiredId,
                CreateStudioRequest("나중에 온 곳", "TAKEN-URL", null),
            )
        }.isInstanceOf(StudioException::class.java)
            .extracting("errorCode")
            .isEqualTo(StudioErrorCode.GALLERY_URL_DUPLICATED)
    }
}

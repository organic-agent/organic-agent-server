package com.soma.wes.studio.fixture

import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.support.TestSequence
import com.soma.wes.user.domain.User
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.workspace.domain.Workspace
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

@Component
class StudioFixture(
    private val studioRepository: StudioRepository,
    private val userFixture: UserFixture,
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val jdbcTemplate: JdbcTemplate,
) {

    /** 스튜디오를 가진 사용자. 갤러리 생성 API는 스튜디오가 있어야 지나간다. */
    fun 작가(): User {
        val user = userFixture.사용자()
        스튜디오(user)
        return user
    }

    fun 스튜디오(owner: User): Studio {
        val workspace = workspaceRepository.save(Workspace.studio("테스트 스튜디오"))
        workspaceMemberRepository.save(
            WorkspaceMember(
                workspaceId = workspace.requiredId,
                userId = owner.requiredId,
                role = WorkspaceRole.OWNER,
            ),
        )
        return studioRepository.save(
            Studio(
                userId = workspace.requiredId,
                name = workspace.name,
                galleryUrl = "studio-${TestSequence.next()}",
            ),
        )
    }

    fun 소유_스튜디오(owner: User): Studio = workspaceMemberRepository.findAllByUserId(owner.requiredId)
        .asSequence()
        .mapNotNull { studioRepository.findById(it.workspaceId).orElse(null) }
        .first()

    /** 운영 정지된 스튜디오로 만든다. 정지는 관리자 앱이 SQL로 거는 상태라 공개 앱 엔티티에는 쓰는 메서드가 없다. */
    fun 정지(studioId: Long, at: ZonedDateTime) {
        jdbcTemplate.update(
            "UPDATE studios SET suspended_at = ?, version = version + 1, updated_at = now() WHERE workspace_id = ?",
            at.toOffsetDateTime(), studioId,
        )
    }
}

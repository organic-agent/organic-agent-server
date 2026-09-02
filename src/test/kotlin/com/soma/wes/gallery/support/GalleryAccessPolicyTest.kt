package com.soma.wes.gallery.support

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.studio.fixture.StudioFixture
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import java.time.ZonedDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class GalleryAccessPolicyTest @Autowired constructor(
    private val policy: GalleryAccessPolicy,
    private val galleryFixture: GalleryFixture,
    private val studioFixture: StudioFixture,
    private val userFixture: UserFixture,
    private val galleryRepository: GalleryRepository,
    private val studioRepository: StudioRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
) {
    @Test
    fun `STUDIO OWNER와 MEMBER는 갤러리 관리자다`() {
        val owner = studioFixture.작가()
        val studio = studioFixture.소유_스튜디오(owner)
        val member = userFixture.사용자()
        workspaceMemberRepository.save(
            WorkspaceMember(studio.workspaceId, member.requiredId, WorkspaceRole.MEMBER),
        )
        val gallery = galleryRepository.save(
            Gallery(studio.workspaceId, owner.requiredId, "본식", GalleryStatus.OPEN),
        )

        assertThat(policy.requireManager(gallery.requiredId, owner.requiredId).requiredId).isEqualTo(gallery.requiredId)
        assertThat(policy.requireManager(gallery.requiredId, member.requiredId).requiredId).isEqualTo(gallery.requiredId)
    }

    @Test
    fun `STUDIO 관리자는 초대 멤버가 아니면 선택 편집자가 아니다`() {
        val fixture = galleryFixture.멤버와_열린_갤러리()

        assertThatThrownBy {
            policy.requireSelectionEditor(fixture.galleryId, fixture.photographer.requiredId)
        }.isInstanceOf(GalleryException::class.java)
            .extracting("errorCode")
            .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        assertThat(policy.requireSelectionEditor(fixture.galleryId, fixture.member.requiredId).requiredId)
            .isEqualTo(fixture.galleryId)
    }

    @Test
    fun `PERSONAL 소유자는 관리자이자 선택 편집자다`() {
        val owner = userFixture.사용자()
        val personal = workspaceRepository.findByPersonalOwnerUserId(owner.requiredId)!!
        val gallery = galleryRepository.save(
            Gallery(personal.requiredId, owner.requiredId, "개인 본식", GalleryStatus.OPEN),
        )

        assertThat(policy.requireManager(gallery.requiredId, owner.requiredId).requiredId).isEqualTo(gallery.requiredId)
        assertThat(policy.requireSelectionEditor(gallery.requiredId, owner.requiredId).requiredId)
            .isEqualTo(gallery.requiredId)
    }

    @Test
    fun `정지된 STUDIO의 멤버십은 갤러리 관리 권한을 주지 않는다`() {
        val owner = studioFixture.작가()
        val studio = studioFixture.소유_스튜디오(owner)
        val gallery = galleryRepository.save(
            Gallery(studio.workspaceId, owner.requiredId, "본식", GalleryStatus.OPEN),
        )
        studio.suspendedAt = ZonedDateTime.now()
        studioRepository.saveAndFlush(studio)

        assertThatThrownBy { policy.requireManager(gallery.requiredId, owner.requiredId) }
            .isInstanceOf(GalleryException::class.java)
            .extracting("errorCode")
            .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
    }
}

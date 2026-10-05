package com.soma.wes.gallery.support

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.PersonalGalleryFixture
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
    private val personalGalleryFixture: PersonalGalleryFixture,
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
    fun `PERSONAL 갤러리는 목표일이 지나도 두 참여자가 계속 고르고 댓글을 쓴다`() {
        val personal = personalGalleryFixture.파트너와_개인_갤러리()
        galleryFixture.마감_지남(personal.galleryId)

        for (userId in listOf(personal.ownerId, personal.partnerId)) {
            assertThat(policy.requireSelectionEditor(personal.galleryId, userId).requiredId).isEqualTo(personal.galleryId)
            assertThat(policy.requireParticipantWriter(personal.galleryId, userId).requiredId).isEqualTo(personal.galleryId)
        }
    }

    @Test
    fun `PERSONAL 갤러리도 이용 기간이 끝나면 고를 수 없다`() {
        val personal = personalGalleryFixture.파트너와_개인_갤러리()
        val gallery = galleryRepository.findById(personal.galleryId).orElseThrow()
        gallery.planExpiresAt = ZonedDateTime.now().minusMinutes(1)
        galleryRepository.saveAndFlush(gallery)

        assertThatThrownBy { policy.requireSelectionEditor(personal.galleryId, personal.partnerId) }
            .isInstanceOf(GalleryException::class.java)
            .extracting("errorCode")
            .isEqualTo(GalleryErrorCode.GALLERY_ARCHIVED)
    }

    @Test
    fun `STUDIO 갤러리는 선택 마감이 지나면 초대 멤버가 고를 수 없다`() {
        val fixture = galleryFixture.멤버와_열린_갤러리()
        galleryFixture.마감_지남(fixture.galleryId)

        assertThatThrownBy { policy.requireSelectionEditor(fixture.galleryId, fixture.member.requiredId) }
            .isInstanceOf(GalleryException::class.java)
            .extracting("errorCode")
            .isEqualTo(GalleryErrorCode.SELECTION_DEADLINE_PASSED)
        assertThatThrownBy { policy.requireParticipantWriter(fixture.galleryId, fixture.member.requiredId) }
            .isInstanceOf(GalleryException::class.java)
            .extracting("errorCode")
            .isEqualTo(GalleryErrorCode.SELECTION_DEADLINE_PASSED)
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

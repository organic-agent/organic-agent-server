package com.soma.wes.gallery.fixture

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.studio.fixture.StudioFixture
import com.soma.wes.user.domain.User
import com.soma.wes.user.fixture.UserFixture
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

@Component
class GalleryFixture(
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val studioFixture: StudioFixture,
    private val userFixture: UserFixture,
) {

    /** 열린 갤러리와 양쪽 사람. 부부의 쓰기가 도는 상태가 대부분 테스트의 출발점이다. */
    fun 멤버와_열린_갤러리(
        maxSelectablePhotoCount: Int? = null,
        maxRetouchRoundCount: Int? = null,
    ): OpenGallery {
        val photographer = studioFixture.작가()
        val studio = studioFixture.소유_스튜디오(photographer)
        val gallery = galleryRepository.save(
            Gallery(
                workspaceId = studio.id!!,
                createdByUserId = photographer.requiredId,
                title = "본식",
                status = GalleryStatus.OPEN,
                maxSelectablePhotoCount = maxSelectablePhotoCount,
                maxRetouchRoundCount = maxRetouchRoundCount,
            ),
        )

        val member = 멤버(gallery.requiredId)

        return OpenGallery(photographer, member, gallery.requiredId)
    }

    /** 초대를 거치지 않고 부부 멤버를 만들어 넣는다. */
    fun 멤버(galleryId: Long): User {
        val member = userFixture.사용자()
        galleryMemberRepository.save(GalleryMember(galleryId = galleryId, userId = member.id!!))
        return member
    }

    /** 부부에게 보이려면 갤러리가 DRAFT를 벗어나야 한다. HTTP로 만든 갤러리를 연다. */
    fun 열기(galleryId: Long) {
        val gallery = galleryRepository.findById(galleryId).orElseThrow()
        gallery.open()
        galleryRepository.saveAndFlush(gallery)
    }

    /** 계약 보정 횟수를 나중에 바꾼다. 회차를 만든 뒤 계약이 줄어드는 상황을 만들 때 쓴다. */
    fun 보정_횟수_변경(galleryId: Long, maxRetouchRoundCount: Int?) {
        val gallery = galleryRepository.findById(galleryId).orElseThrow()
        gallery.changeMaxRetouchRoundCount(maxRetouchRoundCount)
        galleryRepository.saveAndFlush(gallery)
    }

    /**
     * 선택 마감을 과거로 밀어 부부·하객의 쓰기를 잠근다.
     *
     * `changeSelectionDeadline`을 쓰지 않는다 — 지난 기한은 그쪽에서 막힌다. 여기서 필요한 것은
     * 시간이 흘러 기한이 지나버린 **상태**라 필드를 직접 세운다.
     */
    fun 마감_지남(galleryId: Long) {
        val gallery = galleryRepository.findById(galleryId).orElseThrow()
        gallery.selectionDeadline = ZonedDateTime.now().minusDays(1)
        galleryRepository.saveAndFlush(gallery)
    }
}

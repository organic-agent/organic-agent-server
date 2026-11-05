package com.soma.wes.gallery.fixture

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.studio.fixture.StudioFixture
import com.soma.wes.user.domain.User
import com.soma.wes.user.fixture.UserFixture
import java.time.Clock
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

@Component
class GalleryFixture(
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val studioFixture: StudioFixture,
    private val userFixture: UserFixture,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
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
     * 시간이 흘러 기한이 지나버린 **상태**라 행을 직접 고친다.
     */
    fun 마감_지남(galleryId: Long) {
        jdbcTemplate.update(
            "UPDATE galleries SET selection_deadline = ?, version = version + 1, updated_at = now() WHERE id = ?",
            ZonedDateTime.now(clock).minusDays(1).toOffsetDateTime(), galleryId,
        )
    }

    /**
     * 상태만 바꾼다. 운영의 상태 전이(`open`·`close`)는 단계·작업 상태까지 함께 옮기고 DRAFT로 되돌리는
     * 경로가 없다. 여기서 필요한 것은 "아직 열리지 않았거나 닫힌 갤러리"라는 **상태**라 행을 직접 고친다.
     */
    fun 상태_변경(galleryId: Long, status: GalleryStatus) {
        jdbcTemplate.update(
            "UPDATE galleries SET status = ?, version = version + 1, updated_at = now() WHERE id = ?",
            status.name, galleryId,
        )
    }

    /** 이용 기간 끝을 정한다. 지난 시각을 주면 기간이 끝나버린 갤러리가 된다(운영에선 시간이 흘러 생기는 상태). */
    fun 이용_기한(galleryId: Long, expiresAt: ZonedDateTime) {
        jdbcTemplate.update(
            "UPDATE galleries SET plan_expires_at = ?, version = version + 1, updated_at = now() WHERE id = ?",
            expiresAt.toOffsetDateTime(), galleryId,
        )
    }

    /** 이용권의 사진 장수 한도를 정한다. 한도에 닿는 업로드를 볼 때 쓴다. */
    fun 사진_한도(galleryId: Long, maxPhotoCount: Int) {
        jdbcTemplate.update(
            "UPDATE galleries SET plan_max_photo_count = ?, version = version + 1, updated_at = now() WHERE id = ?",
            maxPhotoCount, galleryId,
        )
    }

    /** 폴더를 한 번 저장해야 선택할 수 있는 신규 갤러리로 만든다(`Gallery.create`가 세우는 값). */
    fun 폴더_정리_필수(galleryId: Long) {
        jdbcTemplate.update(
            "UPDATE galleries SET photo_organization_required = true, version = version + 1, updated_at = now() WHERE id = ?",
            galleryId,
        )
    }

    /** 부부 멤버를 갤러리에서 뺀다. 행은 남고 `deleted_at`만 채워진다. */
    fun 멤버_제거(galleryId: Long, userId: Long, at: ZonedDateTime) {
        jdbcTemplate.update(
            "UPDATE gallery_members SET deleted_at = ?, version = version + 1, updated_at = now() WHERE gallery_id = ? AND user_id = ?",
            at.toOffsetDateTime(), galleryId, userId,
        )
    }
}

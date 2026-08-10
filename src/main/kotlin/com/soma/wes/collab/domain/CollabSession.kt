package com.soma.wes.collab.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.ZonedDateTime

/**
 * 갤러리 하나에 열리는 하객 협업 세션. 부부가 고른 사진을 하객에게 보여주고 의견을 받는다.
 *
 * [com.soma.wes.gallery.domain.GalleryMember]가 아닌 이유는 `user_id`가 NOT NULL이라 계정 없는
 * 사람은 멤버 행 자체를 만들 수 없기 때문이다. [com.soma.wes.gallery.domain.GalleryInvite]와도
 * 다르다 — 초대는 계정을 만들어 멤버가 되는 입구이고, 이쪽은 계정을 만들지 않고 보고 말하는
 * 창이다. 그래서 이 링크로는 최종 선택 앨범을 건드릴 수 없다.
 *
 * **갤러리당 여러 개다.** 부부는 묶음마다 물어볼 상대가 다르다 — 본식 후보는 부모님께, 2부
 * 사진은 친구들에게. 세션이 하나뿐이면 그 둘을 한 링크에 섞어 보내야 하고, 그러면 돌아온 의견도
 * 누구에게 무엇을 물어서 나온 것인지 갈라지지 않는다. 링크가 엉뚱한 곳에 퍼졌을 때 세션을 새로
 * 만들지 않고 [revoke] 후 [reissueToken]으로 토큰만 갈아끼우는 것은 그대로다 — 이미 받은 댓글과
 * 반응은 세션에 매달려 있어서, 세션을 새로 만들면 그것들이 남겨진다.
 *
 * 여러 개가 되는 순간 [name]이 필요해진다. "어느 링크였더라"가 곧바로 생기는데 [shareToken]은
 * 사람이 알아볼 수 있는 값이 아니다.
 *
 * 어느 폴더에서 왔는지는 남기지 않는다. 폴더에서 열 때 그 시점의 사진을
 * [com.soma.wes.collab.domain.CollabPhoto] 행으로 **복사**할 뿐이다 — 가리키게 두면 부부가 폴더를
 * 고치거나 지웠을 때 하객이 보던 사진이 발밑에서 바뀐다.
 * [com.soma.wes.folder.domain.PhotoFolder]가 출발점인 클러스터를 가리키지 않는 것과 같은 판단이고,
 * 출처는 기본값으로 물려받은 이름에만 남는다.
 *
 * 만료 시각이 없다. 링크의 수명은 시계가 아니라 부부가 정한다([revoke]) — 청첩장처럼 돌다가
 * 몇 주 뒤에 열어보는 사람이 있어서, 날짜로 끊으면 그 사람에게는 이유 없이 닫힌 문이 된다.
 */
@Entity
@Table(
    name = "collab_sessions",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_collab_sessions_share_token", columnNames = ["share_token"]),
    ],
    indexes = [
        Index(name = "idx_collab_sessions_gallery_id", columnList = "gallery_id"),
    ],
)
class CollabSession(

    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    /** 부부가 링크를 구분하려고 붙인 이름. 하객에게도 첫 화면에 보인다. */
    @Column(nullable = false, length = MAX_NAME_LENGTH)
    var name: String,

    /** 링크에 실리는 값. 추측할 수 없어야 하므로 생성은 `SecureTokenGenerator`가 맡는다. */
    @Column(name = "share_token", nullable = false, length = 255)
    var shareToken: String,

    /** 부부가 링크를 거둬들인 시각. 링크가 엉뚱한 곳에 퍼졌을 때 쓴다. */
    @Column(name = "revoked_at")
    var revokedAt: ZonedDateTime? = null,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 협업 세션입니다." }

    val isRevoked: Boolean
        get() = revokedAt != null

    /**
     * 링크를 거둬들인다. 이미 폐기됐으면 처음 폐기한 시각을 유지하고 아무것도 하지 않는다.
     * 폐기의 목적은 "못 쓰게 만드는 것"이고 그건 이미 이뤄진 상태라, 버튼을 두 번 눌렀다고
     * 실패를 돌려줄 이유가 없다.
     *
     * 담긴 사진과 받은 의견은 지우지 않는다. 부부가 끊고 싶은 것은 링크이지, 하객이 남겨준
     * 말이 아니다.
     */
    fun revoke(at: ZonedDateTime) {
        if (isRevoked) {
            return
        }
        revokedAt = at
    }

    /**
     * 새 링크를 발급한다. 폐기 상태였다면 함께 풀린다.
     *
     * 같은 토큰을 되살리지 않는다 — 되살리면 링크가 퍼진 그 단톡방이 다시 열린다.
     * 폐기의 유일한 의미는 "그 주소를 아는 사람들이 더는 못 들어온다"는 것이다.
     */
    fun reissueToken(shareToken: String) {
        this.shareToken = shareToken
        revokedAt = null
    }

    fun rename(name: String) {
        this.name = normalizeName(name)
    }

    companion object {
        const val MAX_NAME_LENGTH = 100

        /**
         * 앞뒤 공백을 떼고 길이를 확인한다.
         *
         * 컨트롤러의 `@Valid`에만 맡기지 않는다 — 그 검증은 컨트롤러를 거칠 때만 돌고, 서비스를
         * 다른 곳에서 부르면 통째로 건너뛴다. 이름은 부부가 링크를 고르는 유일한 단서라
         * 공백만으로 된 이름이 들어오면 화면에는 이름 없는 링크 두 개가 나란히 남는다.
         */
        fun normalizeName(name: String): String {
            val trimmed = name.trim()
            require(trimmed.isNotEmpty()) { "협업 세션 이름은 비어 있을 수 없습니다." }
            require(trimmed.length <= MAX_NAME_LENGTH) { "협업 세션 이름은 ${MAX_NAME_LENGTH}자를 넘을 수 없습니다." }
            return trimmed
        }

        fun of(galleryId: Long, name: String, shareToken: String) = CollabSession(
            galleryId = galleryId,
            name = normalizeName(name),
            shareToken = shareToken,
        )
    }
}

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

/**
 * 협업 세션에 담긴 사진 한 장. 하객에게는 여기 담긴 것만 보인다.
 *
 * 갤러리 전체를 열지 않는 이유는 물어보는 목적이 다르기 때문이다. 부부는 수천 장을 훑어
 * 추리고, 하객에게는 그중 고민되는 몇 장을 보여준다. 전부 보여주면 아무도 끝까지 넘기지 않고,
 * 그렇게 받은 반응은 앞쪽 몇 장에만 쏠린다.
 *
 * [com.soma.wes.photo.domain.Photo]를 참조만 하고 복사하지 않는다. 댓글과 반응은 이 행에
 * 매달리므로, 사진을 뺐다가 다시 담으면 그때 받은 의견은 함께 사라진다 — 세션에서 뺀다는 것은
 * "이 사진은 더 묻지 않겠다"는 뜻이라 그 편이 맞다.
 */
@Entity
@Table(
    name = "collab_photos",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_collab_photos_session_photo",
            columnNames = ["collab_session_id", "photo_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_collab_photos_collab_session_id", columnList = "collab_session_id"),
    ],
)
class CollabPhoto(

    @Column(name = "collab_session_id", nullable = false, updatable = false)
    val collabSessionId: Long,

    @Column(name = "photo_id", nullable = false, updatable = false)
    val photoId: Long,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 협업 사진입니다." }
}

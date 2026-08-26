package com.soma.wes.gallery.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.SQLRestriction
import java.time.ZonedDateTime

/**
 * 갤러리에 들어온 당사자. 예비 부부가 갤러리에서 무엇을 할 수 있는지는 전부 이 행이 답한다.
 *
 * 초대를 수락해야만 생기므로 **행의 존재 자체가 수락을 뜻한다.** 수락 여부 컬럼을 따로 두지 않는다.
 * 수락 시각은 [createdAt]이고, 들어올 권한은 [GalleryInvite]가 갖는다.
 *
 * 신랑/신부를 구분하는 컬럼은 두지 않는다. 무엇도 가르지 않는 값인 데다,
 * 사진 선택은 부부가 함께 채우는 앨범 하나로 다룰 예정이라 누가 넣었는지도 남기지 않는다.
 */
@Entity
@SQLRestriction("deleted_at is null")
@Table(
    name = "gallery_members",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_gallery_members_gallery_id_user_id",
            columnNames = ["gallery_id", "user_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_gallery_members_user_id", columnList = "user_id"),
    ],
)
class GalleryMember(

    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: Long,

) : BaseEntity() {

    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 멤버입니다." }

    companion object {

        /**
         * 갤러리 하나에 들어올 수 있는 인원. 부부 두 사람이다.
         *
         * 공동 계정을 쓰지 않으므로 한 링크를 신랑과 신부가 각자 눌러 두 행이 생긴다.
         * 수락에 작가의 승인 절차가 없어서, 링크가 퍼졌을 때 이 상한이 유일한 방어선이다.
         */
        const val MAX_PER_GALLERY = 2
    }
}

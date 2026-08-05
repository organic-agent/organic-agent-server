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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}

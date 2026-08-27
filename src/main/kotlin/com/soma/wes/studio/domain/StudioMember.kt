package com.soma.wes.studio.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.SQLRestriction
import java.time.ZonedDateTime

/** 스튜디오 소유권과 별도로 부여되는 실제 제품 운영 권한. */
@Entity
@SQLRestriction("deleted_at is null")
@Table(
    name = "studio_members",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_studio_members_studio_user", columnNames = ["studio_id", "user_id"]),
    ],
)
class StudioMember(
    @Column(name = "studio_id", nullable = false, updatable = false)
    val studioId: Long,

    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val role: StudioMemberRole,
) : BaseEntity() {
    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}

enum class StudioMemberRole {
    OWNER,
    MEMBER,
}

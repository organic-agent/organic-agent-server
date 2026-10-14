package com.soma.wes.workspace.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.ZonedDateTime
import org.hibernate.annotations.SQLRestriction

@Entity
@SQLRestriction("deleted_at is null")
@Table(
    name = "workspaces",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_workspaces_personal_owner", columnNames = ["personal_owner_user_id"]),
    ],
    indexes = [
        Index(name = "idx_workspaces_type", columnList = "type"),
    ],
)
class Workspace(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    val type: WorkspaceType,

    @Column(nullable = false, length = 100)
    var name: String,

    /** PERSONAL 작업공간을 사용자당 하나로 제한하는 DB 소유 키다. STUDIO면 null이다. */
    @Column(name = "personal_owner_user_id", updatable = false)
    val personalOwnerUserId: Long? = null,
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 작업공간입니다." }

    companion object {
        fun personal(userId: Long, name: String) = Workspace(
            type = WorkspaceType.PERSONAL,
            name = name,
            personalOwnerUserId = userId,
        )

        fun studio(name: String) = Workspace(
            type = WorkspaceType.STUDIO,
            name = name,
        )
    }
}

enum class WorkspaceType {
    PERSONAL,
    STUDIO,
}

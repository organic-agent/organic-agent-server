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
    name = "workspace_members",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_workspace_members_workspace_user", columnNames = ["workspace_id", "user_id"]),
    ],
    indexes = [
        Index(name = "idx_workspace_members_user", columnList = "user_id, workspace_id"),
    ],
)
class WorkspaceMember(
    @Column(name = "workspace_id", nullable = false, updatable = false)
    val workspaceId: Long,

    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: WorkspaceRole,
) : BaseEntity() {
    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 작업공간 구성원입니다." }
}

enum class WorkspaceRole {
    OWNER,
    MEMBER,
}

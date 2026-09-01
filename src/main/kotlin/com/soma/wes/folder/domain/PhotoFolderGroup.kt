package com.soma.wes.folder.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.SQLRestriction
import java.time.ZonedDateTime

/**
 * 자식폴더들을 품는 부모폴더.
 */
@Entity
@SQLRestriction("deleted_at is null")
@Table(
    name = "photo_folder_groups",
    indexes = [
        Index(name = "idx_photo_folder_groups_gallery_id", columnList = "gallery_id"),
    ],
)
class PhotoFolderGroup(

    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Embedded
    var name: FolderName,

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, updatable = false, length = 20)
    val origin: FolderOrigin = FolderOrigin.MANUAL,

    /**
     * 이 부모를 만든 naming 잡. 한 번의 AI 폴더 생성이 만든 부모들은 이 값이 같은 한 세트다 —
     * 프론트가 세트 단위로 접거나 지우고, 추천이 기준 세트를 고른다. [FolderOrigin.MANUAL]은 null.
     */
    @Column(name = "analysis_job_id", updatable = false)
    val analysisJobId: Long? = null,

) : BaseEntity() {

    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = id ?: error("아직 저장되지 않은 PhotoFolderGroup 이다")

    fun rename(name: String) {
        this.name = FolderName.of(name)
    }

    companion object {
        fun of(galleryId: Long, name: String) = PhotoFolderGroup(
            galleryId = galleryId,
            name = FolderName.of(name),
        )

        /** AI 세트의 부모 하나. 이름은 컨셉 배정의 큰 분류에서 오므로 [FolderName.of]의 검증을 그대로 지난다. */
        fun aiOf(galleryId: Long, name: String, analysisJobId: Long) = PhotoFolderGroup(
            galleryId = galleryId,
            name = FolderName.of(name),
            origin = FolderOrigin.AI,
            analysisJobId = analysisJobId,
        )
    }
}

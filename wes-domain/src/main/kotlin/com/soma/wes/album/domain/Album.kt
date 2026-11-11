package com.soma.wes.album.domain

import com.soma.wes.album.exception.AlbumErrorCode
import com.soma.wes.album.exception.AlbumException
import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.ZonedDateTime

@Entity
@Table(name = "gallery_albums")
class Album private constructor(
    @Column(name = "gallery_id", nullable = false, updatable = false, unique = true)
    val galleryId: Long,

    studioDocument: String,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "studio_document", nullable = false)
    var studioDocument: String = studioDocument
        protected set

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sent_document", nullable = true)
    var sentDocument: String? = null
        protected set

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "client_document", nullable = true)
    var clientDocument: String? = null
        protected set

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "confirmed_document", nullable = true)
    var confirmedDocument: String? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "share_mode", nullable = true, length = 10)
    var shareMode: AlbumShareMode? = null
        protected set

    @Column(name = "sent_at", nullable = true)
    var sentAt: ZonedDateTime? = null
        protected set

    /** 명시적 재발송·제출은 문서 내용이 같아도 이전 요청의 버전을 만료시킨다. */
    @Column(name = "revision", nullable = false)
    var revision: Long = 1
        protected set

    fun requireRevision(expectedRevision: Long) {
        if (revision != expectedRevision) throw AlbumException(AlbumErrorCode.REVISION_MISMATCH)
    }

    fun saveStudio(document: String) {
        studioDocument = document
        revision++
    }

    fun send(document: String, mode: AlbumShareMode, at: ZonedDateTime) {
        studioDocument = document
        sentDocument = document
        shareMode = mode
        sentAt = at
        clientDocument = null
        revision++
    }

    fun saveClient(document: String) {
        clientDocument = document
        revision++
    }

    fun confirm(document: String) {
        confirmedDocument = document
        revision++
    }

    companion object {
        fun of(galleryId: Long, studioDocument: String): Album =
            Album(galleryId = galleryId, studioDocument = studioDocument)
    }
}

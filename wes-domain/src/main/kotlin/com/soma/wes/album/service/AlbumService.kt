package com.soma.wes.album.service

import com.soma.wes.activity.support.ActivityRecorder
import com.soma.wes.album.domain.Album
import com.soma.wes.album.domain.AlbumShareMode
import com.soma.wes.album.domain.AlbumStatus
import com.soma.wes.album.dto.AlbumLayoutDto
import com.soma.wes.album.dto.request.ConfirmAlbumRequest
import com.soma.wes.album.dto.request.ReturnAlbumRequest
import com.soma.wes.album.dto.request.SaveAlbumRequest
import com.soma.wes.album.dto.request.SendAlbumRequest
import com.soma.wes.album.dto.response.AlbumPhotosResponse
import com.soma.wes.album.dto.response.AlbumResponse
import com.soma.wes.album.exception.AlbumErrorCode
import com.soma.wes.album.exception.AlbumException
import com.soma.wes.album.repository.AlbumRepository
import com.soma.wes.album.support.AlbumLayouts
import com.soma.wes.album.support.AlbumPhotoLoader
import com.soma.wes.gallery.config.GalleryLifecycleProperties
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryStage
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.service.port.UserNotificationPublisher
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.service.port.PhotoStorage
import com.soma.wes.retouch.support.RetouchResultLoader
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.ZonedDateTime

@Service
class AlbumService(
    private val access: GalleryAccessPolicy,
    private val albums: AlbumRepository,
    private val photos: AlbumPhotoLoader,
    private val results: RetouchResultLoader,
    private val photoStorage: PhotoStorage,
    private val storageProperties: StorageProperties,
    private val lifecycleProperties: GalleryLifecycleProperties,
    private val galleryMembers: GalleryMemberRepository,
    private val workspaceMembers: WorkspaceMemberRepository,
    private val notifications: UserNotificationPublisher,
    private val activity: ActivityRecorder,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    /** 갤러리 상태와 문서는 같은 시점의 값으로 읽는다. 초안은 고객에게 공개하지 않는다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun get(galleryId: Long, userId: Long): AlbumResponse {
        val gallery = access.requireStudioAlbumViewer(galleryId, userId)
        val studio = access.isStudioManager(galleryId, userId)
        if (!studio) requireSent(gallery)

        val album = requireAlbum(galleryId)
        return responseOf(gallery, album, photos.findSelectedIds(galleryId), studio = studio)
    }

    /** 스튜디오는 초안 만들기 전에도 사진을 불러온다. 고객은 앨범을 받은 뒤에만 불러온다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun listPhotos(galleryId: Long, userId: Long): AlbumPhotosResponse {
        val gallery = access.requireStudioAlbumViewer(galleryId, userId)
        if (!access.isStudioManager(galleryId, userId)) requireSent(gallery)

        val selected = photos.findSelectedIds(galleryId)
        val keys = results.findLatestSentKeys(galleryId, selected)
        return AlbumPhotosResponse(
            viewUrlTtlSeconds = storageProperties.viewUrlTtl.seconds,
            photos = selected.map { id ->
                AlbumPhotosResponse.SelectedPhoto(photoId = id, resultUrl = keys[id]?.let { photoStorage.presignView(it) })
            },
        )
    }

    /** 첫 저장은 버전 0이다. 그 뒤 스튜디오 초안을 저장해도 고객에게 보낸 문서와 사본은 보존한다. */
    @Transactional
    fun saveStudio(galleryId: Long, userId: Long, request: SaveAlbumRequest): AlbumResponse {
        val gallery = access.requireStudioAlbumManager(galleryId, userId)
        gallery.requireAlbumDraftWritable(ZonedDateTime.now(clock))

        val existing = albums.findByGalleryId(galleryId)
        if (existing == null && request.expectedRevision != 0L) throw AlbumException(AlbumErrorCode.REVISION_MISMATCH)
        existing?.requireRevision(request.expectedRevision)
        val previous = existing?.let { decode(it.studioDocument) }
        if (previous != null && previous.page != request.album.page) throw AlbumException(AlbumErrorCode.PAGE_SIZE_LOCKED)

        val selected = photos.findSelectedIds(galleryId)
        AlbumLayouts.validate(request.album, selected)
        val document = encode(AlbumLayouts.preserveRemoved(request.album, previous, selected))
        val album = if (existing == null) {
            gallery.markAlbumDrafted()
            Album.of(galleryId, document)
        } else {
            existing.saveStudio(document)
            existing
        }
        return savedResponse(gallery, album, selected, studio = true)
    }

    /** 재발송은 기존 고객 사본을 닫고 현재 초안으로 새 사본을 연다. 보기 전용 발송은 즉시 확정한다. */
    @Transactional
    fun send(galleryId: Long, userId: Long, request: SendAlbumRequest): AlbumResponse {
        val gallery = access.requireStudioAlbumManager(galleryId, userId)
        requireAlbumStage(gallery)

        val album = requireAlbum(galleryId)
        album.requireRevision(request.expectedRevision)
        val selected = photos.findSelectedIds(galleryId)
        val draft = AlbumLayouts.reconcile(decode(album.studioDocument), selected)
        AlbumLayouts.requireComplete(draft, selected)
        val now = ZonedDateTime.now(clock)
        val document = encode(draft)

        album.send(document = document, mode = request.mode, at = now)
        notifyClients(gallery, UserNotificationType.ALBUM_SENT, "앨범이 도착했습니다")
        when (request.mode) {
            AlbumShareMode.VIEW -> finish(gallery, album, document, now)
            AlbumShareMode.EDIT -> gallery.markAlbumSent()
        }
        return savedResponse(gallery, album, selected, studio = true)
    }

    /** 고객은 최신 발송본의 틀 안에서 배치와 크롭만 수정한다. */
    @Transactional
    fun saveClient(galleryId: Long, userId: Long, request: SaveAlbumRequest): AlbumResponse {
        val gallery = access.requireStudioAlbumClient(galleryId, userId)
        requireClientEditing(gallery)

        val album = requireAlbum(galleryId)
        album.requireRevision(request.expectedRevision)
        val sent = sentLayout(album)
        AlbumLayouts.requireSameLayout(sent, request.album)

        val selected = photos.findSelectedIds(galleryId)
        AlbumLayouts.validate(request.album, selected)
        val previous = album.clientDocument?.let { decode(it) } ?: sent
        album.saveClient(encode(AlbumLayouts.preserveRemoved(request.album, previous, selected)))
        return savedResponse(gallery, album, selected, studio = false)
    }

    /** 제출하면 고객 사본을 잠그고 스튜디오에서 발송본과 비교할 수 있게 한다. */
    @Transactional
    fun returnToStudio(galleryId: Long, userId: Long, request: ReturnAlbumRequest): AlbumResponse {
        val gallery = access.requireStudioAlbumClient(galleryId, userId)
        requireClientEditing(gallery)

        val album = requireAlbum(galleryId)
        album.requireRevision(request.expectedRevision)
        val selected = photos.findSelectedIds(galleryId)
        val client = AlbumLayouts.reconcile(clientLayout(album), selected)
        AlbumLayouts.requireComplete(client, selected)

        album.saveClient(encode(client))
        gallery.markAlbumReturned()
        publish(
            gallery = gallery,
            recipients = workspaceMembers.findAllByWorkspaceId(gallery.workspaceId).map { it.userId },
            type = UserNotificationType.ALBUM_RETURNED,
            title = "고객이 앨범을 돌려보냈습니다",
        )
        return savedResponse(gallery, album, selected, studio = false)
    }

    /** 고객 제출본 또는 스튜디오가 검토한 초안을 고정하고 보관 기한을 시작한다. */
    @Transactional
    fun confirm(galleryId: Long, userId: Long, request: ConfirmAlbumRequest): AlbumResponse {
        val gallery = access.requireStudioAlbumManager(galleryId, userId)
        requireAlbumStage(gallery)
        if (gallery.albumStatus != AlbumStatus.RETURNED) throw AlbumException(AlbumErrorCode.INVALID_STATE)

        val album = requireAlbum(galleryId)
        album.requireRevision(request.expectedRevision)
        val selected = photos.findSelectedIds(galleryId)
        val candidate = if (request.useReturned) clientLayout(album) else decode(album.studioDocument)
        val finalLayout = AlbumLayouts.reconcile(candidate, selected)
        AlbumLayouts.requireComplete(finalLayout, selected)

        finish(gallery, album, encode(finalLayout), ZonedDateTime.now(clock))
        return savedResponse(gallery, album, selected, studio = true)
    }

    private fun finish(gallery: Gallery, album: Album, document: String, now: ZonedDateTime) {
        album.confirm(document)
        gallery.markAlbumConfirmed()
        lifecycleProperties.archivedRetentionDays?.let { gallery.archiveAfter(it, now) }
        notifyClients(gallery, UserNotificationType.ALBUM_CONFIRMED, "앨범이 확정되었습니다")
    }

    private fun requireAlbumStage(gallery: Gallery) {
        if (gallery.stage != GalleryStage.ALBUM) throw AlbumException(AlbumErrorCode.INVALID_STATE)
    }

    private fun requireClientEditing(gallery: Gallery) {
        requireSent(gallery)
        requireAlbumStage(gallery)
        if (gallery.albumStatus != AlbumStatus.SENT_EDIT) throw AlbumException(AlbumErrorCode.INVALID_STATE)
    }

    private fun requireSent(gallery: Gallery) {
        if (gallery.albumStatus == AlbumStatus.NONE || gallery.albumStatus == AlbumStatus.DRAFT) {
            throw AlbumException(AlbumErrorCode.NOT_FOUND)
        }
    }

    private fun requireAlbum(galleryId: Long): Album =
        albums.findByGalleryId(galleryId) ?: throw AlbumException(AlbumErrorCode.NOT_FOUND)

    private fun sentLayout(album: Album): AlbumLayoutDto =
        decode(checkNotNull(album.sentDocument) { "발송된 앨범에 발송본이 없습니다." })

    private fun clientLayout(album: Album): AlbumLayoutDto =
        album.clientDocument?.let { decode(it) } ?: sentLayout(album)

    private fun notifyClients(gallery: Gallery, type: UserNotificationType, title: String) {
        val recipients = galleryMembers.findAllByGalleryId(gallery.requiredId).map { it.userId }
        publish(gallery = gallery, recipients = recipients, type = type, title = title)
    }

    private fun publish(gallery: Gallery, recipients: List<Long>, type: UserNotificationType, title: String) {
        notifications.publish(
            userIds = recipients,
            type = type,
            scope = UserNotificationScope.GALLERY,
            scopeId = gallery.requiredId,
            title = title,
            message = "${gallery.title} · $title",
        )
    }

    private fun savedResponse(gallery: Gallery, album: Album, selected: Set<Long>, studio: Boolean): AlbumResponse {
        val saved = albums.saveAndFlush(album)
        activity.recordGallery(gallery.requiredId)
        return responseOf(gallery, saved, selected, studio)
    }

    private fun responseOf(gallery: Gallery, album: Album, selected: Set<Long>, studio: Boolean): AlbumResponse {
        val status = gallery.albumStatus
        val document = when {
            status == AlbumStatus.CONFIRMED -> checkNotNull(album.confirmedDocument) { "확정된 앨범에 확정본이 없습니다." }
            studio -> album.studioDocument
            else -> album.clientDocument ?: checkNotNull(album.sentDocument) { "고객에게 공개된 발송본이 없습니다." }
        }
        val sent = if (studio) album.sentDocument?.let { AlbumLayouts.reconcile(decode(it), selected) } else null
        val returned = if (studio && status in setOf(AlbumStatus.RETURNED, AlbumStatus.CONFIRMED)) {
            album.clientDocument?.let { AlbumLayouts.reconcile(decode(it), selected) }
        } else {
            null
        }
        val changes = if (sent != null && returned != null) AlbumLayouts.changedFrames(sent, returned) else emptyList()

        val layout = decode(document)
        val visibleLayout = if (status == AlbumStatus.CONFIRMED) layout else AlbumLayouts.reconcile(layout, selected)
        return AlbumResponse(
            status = status,
            revision = album.revision,
            updatedAt = album.updatedAt,
            album = visibleLayout,
            sentAt = album.sentAt,
            shareMode = album.shareMode,
            sentAlbum = sent,
            returnedAlbum = returned,
            changedFrameIds = changes,
        )
    }

    private fun encode(layout: AlbumLayoutDto): String = mapper.writeValueAsString(layout)

    private fun decode(document: String): AlbumLayoutDto = mapper.readValue(document, AlbumLayoutDto::class.java)
}

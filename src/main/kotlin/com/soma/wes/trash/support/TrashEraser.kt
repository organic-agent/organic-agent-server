package com.soma.wes.trash.support

import com.soma.wes.photo.service.port.PhotoStorage
import com.soma.wes.trash.config.TrashProperties
import com.soma.wes.trash.repository.TrashRepository
import com.soma.wes.trash.repository.projection.TrashedPhotoTarget
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * 물리 삭제의 실행부. 휴지통의 즉시 삭제([com.soma.wes.trash.service.TrashService])와
 * 보관 만료 purge([TrashPurgeScheduler])가 같은 절차를 쓴다.
 *
 * 순서는 항상 S3 먼저, DB 나중이다. DB를 먼저 지우면 S3 삭제가 실패했을 때 그 객체를
 * 가리키는 행이 없어 영영 지울 수 없다. 반대는 재시도로 수습된다 — S3 삭제는 없는 키를
 * 다시 지워도 성공하고, 행이 남아 있으면 다음 purge가 같은 대상을 다시 집는다.
 *
 * S3 호출 앞뒤로 짧은 DB 트랜잭션을 둔다. 첫 트랜잭션은 관련 행을 잠그고 관리자 휴지통을
 * 재검사한 뒤 lease claim을 남긴다. 잠금을 해제하고 S3를 지운 다음, 두 번째 트랜잭션이 같은
 * claim token과 범위를 다시 확인하고 DB를 지운다. S3 일부 삭제나 worker crash에서는 claim을
 * 남겨 복원/admin mutation을 막고, lease 만료 뒤 idempotent S3 삭제부터 재시도한다.
 */
@Service
class TrashEraser(
    private val trashRepository: TrashRepository,
    private val photoStorage: PhotoStorage,
    private val properties: TrashProperties,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 갤러리 하나를 사진 원본·미리보기·보정 파일과 함께 물리 삭제한다.
     *
     * false는 대상이 이미 복원됐거나 관리자 batch/child/다른 product claim이 먼저 소유권을
     * 얻었다는 뜻이다. S3 예외는 그대로 전파하되 claim은 lease까지 남긴다.
     */
    fun eraseGallery(galleryId: Long): Boolean {
        val token = UUID.randomUUID()
        val claimed = transactionTemplate.execute {
            trashRepository.lockPurgeCoordinationForGallery(galleryId)
            if (!trashRepository.lockGalleryPurgeScope(galleryId)) return@execute false
            if (trashRepository.isGalleryProtectedByAdmin(galleryId)) return@execute false
            if (trashRepository.hasPhotoClaimInGallery(galleryId)) return@execute false
            val now = ZonedDateTime.now(clock)
            trashRepository.tryAcquirePurgeClaim(
                resourceType = GALLERY,
                resourceId = galleryId,
                token = token,
                now = now,
                leaseUntil = now.plus(CLAIM_LEASE),
            )
        } == true
        if (!claimed) return false

        val photoCount = trashRepository.findAllPhotoTargets(galleryId).size
        val objectKeys = trashRepository.findPurgeObjectKeys(galleryId)
        photoStorage.deleteAll(objectKeys)

        val finalized = transactionTemplate.execute {
            // claim tx와 같은 domain -> claim 순서로 잠가 takeover/finalize 교착을 피한다.
            trashRepository.lockPurgeCoordinationForGallery(galleryId)
            if (!trashRepository.lockGalleryPurgeScope(galleryId)) return@execute false
            if (trashRepository.isGalleryProtectedByAdmin(galleryId)) return@execute false
            if (trashRepository.hasPhotoClaimInGallery(galleryId)) return@execute false
            if (!trashRepository.ownsPurgeClaimsForUpdate(GALLERY, listOf(galleryId), token)) {
                return@execute false
            }
            if (trashRepository.deleteGallery(galleryId) != 1) return@execute false
            check(trashRepository.deleteOwnedPurgeClaims(GALLERY, listOf(galleryId), token) == 1) {
                "갤러리 purge claim 정리에 실패했습니다: $galleryId"
            }
            true
        } == true
        if (!finalized) return false

        log.info(
            "갤러리 물리 삭제: galleryId={}, photos={}, objects={}",
            galleryId, photoCount, objectKeys.size,
        )
        return true
    }

    /** 사진들을 원본·미리보기·보정 파일과 함께 물리 삭제한다. 휴지통 여부 검증은 호출자의 몫이다. */
    fun erasePhotos(targets: List<TrashedPhotoTarget>): Boolean {
        if (targets.isEmpty()) {
            return true
        }
        val photoIds = targets.map { it.photoId }.distinct().sorted()
        val token = UUID.randomUUID()
        val claimedTargets = try {
            transactionTemplate.execute {
                trashRepository.lockPurgeCoordinationForPhotos(photoIds)
                val locked = trashRepository.lockPhotoPurgeScope(photoIds)
                if (locked.map { it.photoId }.toSet() != photoIds.toSet()) throw PurgeClaimRejected()
                if (trashRepository.protectedPhotoIds(photoIds).isNotEmpty()) throw PurgeClaimRejected()
                if (trashRepository.hasGalleryClaimForPhotos(photoIds)) throw PurgeClaimRejected()
                val now = ZonedDateTime.now(clock)
                photoIds.forEach { photoId ->
                    if (
                        !trashRepository.tryAcquirePurgeClaim(
                            resourceType = PHOTO,
                            resourceId = photoId,
                            token = token,
                            now = now,
                            leaseUntil = now.plus(CLAIM_LEASE),
                        )
                    ) {
                        // 앞선 사진 claim도 같은 transaction과 함께 rollback한다.
                        throw PurgeClaimRejected()
                    }
                }
                locked
            }
        } catch (_: PurgeClaimRejected) {
            null
        } ?: return false

        val objectKeys = trashRepository.findPurgeObjectKeysByPhotoIds(photoIds)
        photoStorage.deleteAll(objectKeys)

        val finalized = transactionTemplate.execute {
            trashRepository.lockPurgeCoordinationForPhotos(photoIds)
            val locked = trashRepository.lockPhotoPurgeScope(photoIds)
            if (locked.map { it.photoId }.toSet() != photoIds.toSet()) return@execute false
            if (trashRepository.protectedPhotoIds(photoIds).isNotEmpty()) return@execute false
            if (trashRepository.hasGalleryClaimForPhotos(photoIds)) return@execute false
            if (!trashRepository.ownsPurgeClaimsForUpdate(PHOTO, photoIds, token)) return@execute false
            if (trashRepository.deletePhotos(photoIds) != photoIds.size) return@execute false
            check(trashRepository.deleteOwnedPurgeClaims(PHOTO, photoIds, token) == photoIds.size) {
                "사진 purge claim 정리에 실패했습니다: $photoIds"
            }
            true
        } == true
        if (!finalized) return false

        log.info("사진 물리 삭제: photos={}, objects={}", claimedTargets.size, objectKeys.size)
        return true
    }

    /**
     * 보관 기간이 지난 휴지통 행을 걷는다. 매시 [TrashPurgeScheduler]가 부른다.
     *
     * 갤러리가 먼저다 — 갤러리 purge가 자기 사진(개별 휴지통행 포함)을 cascade로 걷으므로,
     * 사진을 먼저 훑으면 같은 대상을 두 번 지우게 된다. 단위마다 실패를 삼키고 넘어간다.
     * 실패한 행은 `deleted_at`이 그대로라 다음 시각에 다시 대상이 된다.
     *
     * 활성 업로드 URL은 확인하지 않는다. URL 수명(30분)이 보관 기간(기본 7일)보다 훨씬 짧아,
     * 만료된 휴지통 행의 URL은 이미 죽어 있다.
     */
    fun purgeExpired() {
        val cutoff = ZonedDateTime.now(clock) - properties.retention

        trashRepository.findExpiredGalleryIds(cutoff).forEach { galleryId ->
            runCatching { eraseGallery(galleryId) }
                .onFailure { log.error("갤러리 purge 실패, 다음 시각에 재시도한다: galleryId={}", galleryId, it) }
        }

        trashRepository.findExpiredPhotoTargets(cutoff).forEach { target ->
            runCatching { erasePhotos(listOf(target)) }
                .onFailure { log.error("사진 purge 실패, 다음 시각에 재시도한다: photoId={}", target.photoId, it) }
        }
    }

    companion object {

        private const val GALLERY = "GALLERY"
        private const val PHOTO = "PHOTO"
        private val CLAIM_LEASE: Duration = Duration.ofMinutes(15)

        /**
         * 원본 키에서 파생되는 미리보기의 고정 위치. 임베딩 Lambda의 `preview_key_for`,
         * Mock 갤러리 복제의 `MockGalleryService.buildPlans`와 같은 규칙이어야 한다.
         *
         * `previewKey` 컬럼과 별개로 이것까지 지우는 이유: 미리보기 업로드와 컬럼 UPDATE
         * 사이에 삭제가 끼어들면, 컬럼은 null인데 객체는 이미 올라가 있을 수 있다.
         */
        fun expectedPreviewKeyOf(storageKey: String): String =
            "previews/${storageKey.substringBeforeLast('.', storageKey)}.jpg"
    }

    private class PurgeClaimRejected : RuntimeException(null, null, false, false)
}

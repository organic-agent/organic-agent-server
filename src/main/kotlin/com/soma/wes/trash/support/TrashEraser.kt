package com.soma.wes.trash.support

import com.soma.wes.photo.service.PhotoStorage
import com.soma.wes.trash.config.TrashProperties
import com.soma.wes.trash.repository.TrashRepository
import com.soma.wes.trash.repository.projection.TrashedPhotoTarget
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 물리 삭제의 실행부. 휴지통의 즉시 삭제([com.soma.wes.trash.service.TrashService])와
 * 보관 만료 purge([TrashPurgeScheduler])가 같은 절차를 쓴다.
 *
 * 순서는 항상 S3 먼저, DB 나중이다. DB를 먼저 지우면 S3 삭제가 실패했을 때 그 객체를
 * 가리키는 행이 없어 영영 지울 수 없다. 반대는 재시도로 수습된다 — S3 삭제는 없는 키를
 * 다시 지워도 성공하고, 행이 남아 있으면 다음 purge가 같은 대상을 다시 집는다.
 *
 * `@Transactional`이 없는 것은 의도다. S3 호출을 트랜잭션에 넣으면 네트워크를 기다리는
 * 동안 커넥션을 점유하고, 롤백해도 지운 객체는 돌아오지 않는다. DB 삭제는 문장 하나라
 * (갤러리는 FK cascade가 한 문장 안에서 하위를 걷는다) 그 자체로 원자적이다.
 */
@Service
class TrashEraser(
    private val trashRepository: TrashRepository,
    private val photoStorage: PhotoStorage,
    private val properties: TrashProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 갤러리 하나를 사진 원본·미리보기·보정 파일과 함께 물리 삭제한다. 휴지통 여부 검증은 호출자의 몫이다. */
    fun eraseGallery(galleryId: Long) {
        val targets = trashRepository.findAllPhotoTargets(galleryId)
        val retouchKeys = trashRepository.findRetouchObjectKeys(galleryId)
        photoStorage.deleteAll(objectKeysOf(targets) + retouchKeys)
        trashRepository.deleteGallery(galleryId)
        log.info(
            "갤러리 물리 삭제: galleryId={}, photos={}, retouchFiles={}",
            galleryId, targets.size, retouchKeys.size,
        )
    }

    /** 사진들을 원본·미리보기·보정 파일과 함께 물리 삭제한다. 휴지통 여부 검증은 호출자의 몫이다. */
    fun erasePhotos(targets: List<TrashedPhotoTarget>) {
        if (targets.isEmpty()) {
            return
        }
        val retouchKeys = trashRepository.findRetouchObjectKeysByPhotoIds(targets.map { it.photoId })
        photoStorage.deleteAll(objectKeysOf(targets) + retouchKeys)
        trashRepository.deletePhotos(targets.map { it.photoId })
        log.info("사진 물리 삭제: photos={}, retouchFiles={}", targets.size, retouchKeys.size)
    }

    /**
     * 보관 기간이 지난 휴지통 행을 걷는다. 매시 [TrashPurgeScheduler]가 부른다.
     *
     * 갤러리가 먼저다 — 갤러리 purge가 자기 사진(개별 휴지통행 포함)을 cascade로 걷으므로,
     * 사진을 먼저 훑으면 같은 대상을 두 번 지우게 된다. 단위마다 실패를 삼키고 넘어간다.
     * 실패한 행은 `deleted_at`이 그대로라 다음 시각에 다시 대상이 된다.
     *
     * 활성 업로드 URL은 확인하지 않는다. URL 수명(30분)이 보관 기간(3일)보다 훨씬 짧아,
     * 만료된 휴지통 행의 URL은 이미 죽어 있다.
     */
    fun purgeExpired() {
        val cutoff = ZonedDateTime.now(clock) - properties.retention

        trashRepository.findExpiredGalleryIds(cutoff).forEach { galleryId ->
            runCatching { eraseGallery(galleryId) }
                .onFailure { log.error("갤러리 purge 실패, 다음 시각에 재시도한다: galleryId={}", galleryId, it) }
        }

        val expiredPhotos = trashRepository.findExpiredPhotoTargets(cutoff)
        runCatching { erasePhotos(expiredPhotos) }
            .onFailure { log.error("사진 purge 실패, 다음 시각에 재시도한다: photos={}", expiredPhotos.size, it) }
    }

    private fun objectKeysOf(targets: List<TrashedPhotoTarget>): Set<String> =
        targets.flatMapTo(mutableSetOf()) { target ->
            listOfNotNull(target.storageKey, target.previewKey, expectedPreviewKeyOf(target.storageKey))
        }

    companion object {

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
}

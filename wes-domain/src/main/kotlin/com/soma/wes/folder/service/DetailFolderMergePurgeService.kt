package com.soma.wes.folder.service

import com.soma.wes.folder.config.FolderProperties
import com.soma.wes.folder.repository.DetailFolderMergeRepository
import com.soma.wes.folder.repository.DetailFolderRepository
import java.time.Clock
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 되돌릴 시간([FolderProperties.mergeUndoWindow])이 지난 세부 폴더 합치기를 정리한다.
 * 숨은 원본 폴더는 이제 되돌릴 수 없으니 지우고, 남은 합치기 기록도 지운다. 화면 · API에는 이미 보이지 않던 행이라 사용자 동작은 바뀌지 않는다.
 */
@Service
class DetailFolderMergePurgeService(
    private val detailRepository: DetailFolderRepository,
    private val mergeRepository: DetailFolderMergeRepository,
    private val folderProperties: FolderProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun purgeExpired() {
        val cutoff = ZonedDateTime.now(clock).minus(folderProperties.mergeUndoWindow)

        // 순서가 중요하다 — 숨은 원본은 합치기 기록으로만 찾으므로 원본을 먼저 지운다(기록은 CASCADE로 함께 지워진다).
        val hiddenFolders = detailRepository.deleteHiddenByMergesBefore(cutoff)
        val merges = mergeRepository.deleteAllByMergedAtBefore(cutoff)

        if (hiddenFolders > 0 || merges > 0) {
            log.info("event=folder.merge_purged hiddenFolders={} merges={}", hiddenFolders, merges)
        }
    }
}

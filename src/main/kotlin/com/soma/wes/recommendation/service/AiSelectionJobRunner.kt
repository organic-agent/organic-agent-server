package com.soma.wes.recommendation.service

import com.soma.wes.folder.dto.FolderSetDetailDto
import com.soma.wes.folder.support.AiFolderSetReader
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoAnalysisRepository
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.recommendation.domain.AiRecommendation
import com.soma.wes.recommendation.domain.AiSelectionMode
import com.soma.wes.recommendation.dto.RecommendablePhotoDto
import com.soma.wes.recommendation.repository.AiRecommendationRepository
import com.soma.wes.recommendation.repository.AiSelectionJobRepository
import com.soma.wes.recommendation.support.ExactRecommendationQuota
import com.soma.wes.recommendation.support.FolderFitRule
import com.soma.wes.recommendation.support.FolderQuota
import com.soma.wes.recommendation.support.MmrSelector
import com.soma.wes.recommendation.support.RecommendationScoring
import com.soma.wes.selection.repository.PhotoSelectionItemRepository
import com.soma.wes.selection.repository.PhotoSelectionRepository
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.math.round
import kotlin.math.sqrt
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * 폴더별 추천 한 라운드 — AI repo `recommend/draft.run`의 자리다.
 *
 *     폴더마다 f:  후보 = f.photos − 담은 사진 − 거절 − 폴더와 동떨어진 사진([FolderFitRule])
 *                  (이전 라운드 노출은 제외하지 않는다)
 *                  n_f = max(1, round(remaining·|f|/Σ|f'|)),  n_f ≤ ceil(|f|·0.5)
 *                  연사당 1장 → MMR → n_f장, 폴더 안 점수 순위가 rank
 *     미분류(세트에 없는 사진)는 가상 폴더로 같은 규칙.
 *     추천 INSERT + 라운드 확정 → DONE.
 *
 * 품질로 사진을 제거하는 단계는 없다. 트랜잭션은 읽기·적재·닫기 단계마다 짧게 끊는다. 실패는 잡에 FAILED로 남는다.
 */
@Service
class AiSelectionJobRunner(
    private val aiSelectionJobRepository: AiSelectionJobRepository,
    private val aiRecommendationRepository: AiRecommendationRepository,
    private val photoSelectionRepository: PhotoSelectionRepository,
    private val photoSelectionItemRepository: PhotoSelectionItemRepository,
    private val galleryRepository: GalleryRepository,
    private val photoRepository: PhotoRepository,
    private val photoAnalysisRepository: PhotoAnalysisRepository,
    private val folderSetReader: AiFolderSetReader,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun run(jobId: Long) {
        val claimed = transactionTemplate.execute { aiSelectionJobRepository.claim(jobId, ZonedDateTime.now(clock)) }!!
        if (claimed == 0) {
            log.info("AI 추천 잡을 집지 못함(이미 실행 중이거나 끝남): jobId={}", jobId)
            return
        }
        val timing = StageTiming()
        try {
            val saved = transactionTemplate.execute { loadSavedRound(jobId) }
            if (saved != null) {
                finishSavedRound(saved, timing)
                return
            }
            val world = timing.measure("load") { transactionTemplate.execute { load(jobId) }!! }
            val draft = timing.measure("plan") { plan(world) }
            val roundNo = timing.measure("persist") { transactionTemplate.execute { persistRound(world, draft) }!! }
            transactionTemplate.execute {
                val job = aiSelectionJobRepository.findById(jobId).orElseThrow()
                job.finish(summary(world, draft, roundNo, timing), ZonedDateTime.now(clock))
            }
            log.info("AI 추천 잡 완료: jobId={}, round={}, k={}", jobId, roundNo, draft.picks.size)
        } catch (e: Exception) {
            log.error("AI 추천 잡 실패: jobId={}", jobId, e)
            transactionTemplate.execute {
                val job = aiSelectionJobRepository.findById(jobId).orElseThrow()
                job.fail("${e::class.simpleName}: ${e.message}", ZonedDateTime.now(clock))
            }
        }
    }

    // ── 읽기 ──

    /**
     * 추천을 저장한 뒤 닫기 전에 죽은 잡의 복구. 이미 제시한 추천은 다시 계산하지 않는다 — 그사이 선택·분류가
     * 바뀌었어도 저장된 라운드를 그대로 두고 요약만 남겨 닫는다. 거절했거나 휴지통에 들어간 사진은 장수에서 뺀다.
     */
    private fun loadSavedRound(jobId: Long): SavedRound? {
        val job = aiSelectionJobRepository.findById(jobId).orElseThrow()
        val round = job.round ?: return null
        val selection = photoSelectionRepository.findById(job.selectionId).orElseThrow()
        val notRejected = aiRecommendationRepository.findAllBySelectionIdAndRound(job.selectionId, round)
            .filter { it.rejectedAt == null }
        val livePhotoIds = photoRepository.findAllByGalleryIdAndIdIn(selection.galleryId, notRejected.map { it.photoId })
            .mapTo(mutableSetOf()) { it.requiredId }
        val recommendations = notRejected.filter { it.photoId in livePhotoIds }
        return SavedRound(
            jobId = jobId,
            galleryId = selection.galleryId,
            round = round,
            requestedCount = job.targetCount,
            scopeFolderIds = job.detailFolderId?.let(::listOf),
            folderOfPicks = recommendations.map { it.scoreBreakdown["folder"] as? String ?: UNFILED },
            summary = job.result.orEmpty(),
        )
    }

    private fun finishSavedRound(saved: SavedRound, timing: StageTiming) {
        val k = saved.folderOfPicks.size
        val summary = saved.summary + mapOf(
            "gallery" to saved.galleryId, "pipeline" to "v3", "round" to saved.round,
            "k" to k,
            "scopeFolderId" to saved.scopeFolderIds?.singleOrNull(),
            "scopeDetailFolderIds" to saved.scopeFolderIds,
            "requestedCount" to saved.requestedCount,
            "shortfallCount" to saved.requestedCount?.let { (it - k).coerceAtLeast(0) },
            "perFolder" to saved.folderOfPicks.groupingBy { it }.eachCount(),
            "elapsedSeconds" to timing.elapsedSeconds(), "timing" to timing.toMap(), "recovered" to true,
        )
        transactionTemplate.execute {
            aiSelectionJobRepository.findById(saved.jobId).orElseThrow().finish(summary, ZonedDateTime.now(clock))
        }
        log.info("AI 추천 잡 복구 완료: jobId={}, round={}, k={}", saved.jobId, saved.round, k)
    }

    private fun load(jobId: Long): World {
        val job = aiSelectionJobRepository.findById(jobId).orElseThrow()
        val selection = photoSelectionRepository.findById(job.selectionId).orElseThrow()
        val galleryId = selection.galleryId
        val gallery = galleryRepository.findById(galleryId).orElseThrow()

        // 갤러리 전체는 벡터 없는 요약으로 읽는다 — 점수 정규화·피사체 통계가 전체 행을 보지만 벡터는 안 본다.
        val summaryById = photoAnalysisRepository.findAllAnalyzedSummaryByGalleryId(galleryId).associateBy { it.photoId }
        val rows = photoRepository.findAllByGalleryIdOrderByDisplayOrderAsc(galleryId).sortedWith(Photo.DISPLAY_ORDER)
            .mapNotNull { photo -> summaryById[photo.requiredId]?.let(RecommendablePhotoDto::from) }
        if (rows.isEmpty()) throw IllegalStateException("분석 결과가 없다: gallery=$galleryId")

        // 범위. 폴더 하나면 그 폴더만 대상이되, 쿼터(폴더별 n장)는 전체 라운드였을 때와 같은 몫으로 정한다 —
        // 그래야 폴더 하나만 다시 받아도 장수가 널뛰지 않는다. 세트 폴더는 그 몫의 재료로만 읽는다.
        val scopeFolderIds = job.detailFolderId?.let(::listOf)
        val setFolders = job.analysisJobId?.let { folderSetReader.setFolders(galleryId, it) }.orEmpty()
        val folders = when (scopeFolderIds) {
            null -> setFolders.ifEmpty {
                throw IllegalStateException("AI 폴더 세트 ${job.analysisJobId} 에 폴더가 없다")
            }
            else -> scopeFolderIds.map { id ->
                folderSetReader.detailFolder(galleryId, id)
                    ?: throw IllegalStateException("세부폴더 $id 가 없다")
            }
        }

        val selected = photoSelectionItemRepository.findAllBySelectionId(selection.requiredId).mapTo(mutableSetOf()) { it.photoId }
        val rejected = aiRecommendationRepository.findAllBySelectionIdAndRejectedAtIsNotNull(selection.requiredId)
            .mapTo(mutableSetOf()) { it.photoId }
        val previousRound = aiRecommendationRepository.findFirstBySelectionIdOrderByRoundDesc(selection.requiredId)?.round ?: 0

        return World(
            job = JobRef(jobId, job.mode, job.round),
            selectionId = selection.requiredId,
            galleryId = galleryId,
            rows = rows,
            embeddings = loadEmbeddings(rows, folders, scopeFolderIds),
            folders = folders,
            quotaFolders = if (scopeFolderIds == null) folders else setFolders.ifEmpty { folders },
            scopeFolderIds = scopeFolderIds,
            requestedCount = job.targetCount,
            selected = selected,
            rejected = rejected,
            target = gallery.maxSelectablePhotoCount ?: DEFAULT_TARGET,
            previousRound = previousRound,
        )
    }

    /**
     * 벡터는 이번 라운드가 도는 사진만 읽는다 — 폴더 이상치 판정과 MMR이 폴더 안에서만 벡터를 비교하기 때문이다.
     * 폴더 범위면 그 폴더의 사진, 전체 라운드면 세트 전 폴더 + 미분류 = 분석된 사진 전부다.
     * 반환은 [rows]와 같은 인덱스의 배열이고, 범위 밖 행은 빈 배열이다 — 계산은 범위 폴더의 사진 인덱스만 넘기므로 닿지 않는다.
     */
    private fun loadEmbeddings(
        rows: List<RecommendablePhotoDto>,
        folders: List<FolderSetDetailDto>,
        scopeFolderIds: List<Long>?,
    ): Array<FloatArray> {
        val rowIds = rows.map { it.photoId }
        val wanted = if (scopeFolderIds == null) {
            rowIds
        } else {
            val analyzed = rowIds.toSet()
            folders.flatMap { it.photoIds }.filter { it in analyzed }
        }
        val vectorById = photoAnalysisRepository.findAllEmbeddingByPhotoIdIn(wanted)
            .associate { it.photoId to normalized(it.embedding) }
        return Array(rows.size) { vectorById[rowIds[it]] ?: NO_EMBEDDING }
    }

    // ── 계산 ──

    private fun plan(world: World): Draft {
        val rows = world.rows
        val n = rows.size
        val indexById = rows.withIndex().associate { (i, r) -> r.photoId to i }

        // 미분류 가상 폴더 — 세트에 없는 사진(폴더 생성 뒤 업로드분·사용자가 뺀 사진)도 같은 규칙으로.
        // 폴더 범위 잡은 그 폴더만 돈다(미분류 없음). 쿼터의 재료(quotaFolders)는 전체 세트 기준이다.
        val inQuotaSet = world.quotaFolders.flatMapTo(mutableSetOf()) { it.photoIds }
        val unfiled = rows.map { it.photoId }.filter { it !in inQuotaSet }
        val scoped = world.scopeFolderIds != null
        val quotaFolders = world.quotaFolders.map { VirtualFolder(it.detailFolderId, it.conceptName, it.detailName, it.photoIds) } +
            listOfNotNull(unfiled.takeIf { it.isNotEmpty() }?.let { VirtualFolder(null, UNFILED, UNFILED, it) })
        val folders = if (scoped) {
            world.folders.map { VirtualFolder(it.detailFolderId, it.conceptName, it.detailName, it.photoIds) }
        } else {
            quotaFolders
        }

        val selectedIdx = world.selected.mapNotNull { indexById[it] }
        val nSelected = selectedIdx.size
        val remaining = world.requestedCount ?: (world.target - nSelected)

        // 점수 (갤러리 내 백분위 그대로)
        val priorRaw = RecommendationScoring.prior(
            DoubleArray(n) { rows[it].technicalPct },
            DoubleArray(n) { rows[it].aestheticPct },
        )
        val types = rows.map { it.subjects }
        val prefOn = SUBJECTS_TRUSTED && nSelected >= PREF_MIN_SELECTED && types.any { it != UNKNOWN }
        val stats = if (prefOn) {
            val mask = BooleanArray(n).also { m -> selectedIdx.forEach { m[it] = true } }
            RecommendationScoring.typeStats(types, mask)
        } else {
            null
        }
        val combined = RecommendationScoring.combine(priorRaw, if (prefOn) types else null, stats)
        val score = combined.score

        // 폴더별 선택
        val exclude = (selectedIdx + world.rejected.mapNotNull { indexById[it] }).toSet()
        // 범위 폴더가 세트에 없는 폴더(사용자가 만든 것)면 쿼터 재료에 끼워 넣어 같은 규칙으로 몫을 정한다.
        val quotaSizes = (quotaFolders + folders.filter { f -> quotaFolders.none { it.folderId == f.folderId } })
            .filter { it.photoIds.isNotEmpty() }
            .associate { it.folderId to it.photoIds.size }
        val burstIds = IntArray(n) { rows[it].burstId }

        val picks = mutableListOf<PlannedPick>()
        val perFolder = linkedMapOf<String, Int>()
        val misfitPhotoIds = mutableListOf<Long>()
        val membersByFolder = folders.associate { folder ->
            val allMembers = folder.photoIds.mapNotNull { indexById[it] }
            // 폴더와 동떨어진 사진(실수로 옮겨 온 사진)은 후보에서 뺀다. 미분류는 원래 잡동사니라 판정하지 않는다.
            val misfit = if (folder.folderId == null) emptySet() else FolderFitRule.misfits(world.embeddings, allMembers)
            if (misfit.isNotEmpty()) {
                misfitPhotoIds += misfit.map { rows[it].photoId }
                log.info("폴더 {}›{} 에서 동떨어진 사진 {}장 제외: {}", folder.conceptName, folder.name, misfit.size, misfit.map { rows[it].photoId })
            }
            val members = allMembers.filter { it !in exclude && it !in misfit }
            folder.folderId to members
        }
        val quota = if (world.requestedCount == null) FolderQuota.quota(quotaSizes, remaining) else
            ExactRecommendationQuota.allocate(membersByFolder.mapValues { (_, members) -> members.map { burstIds[it] }.distinct().size }, remaining)
        folders.sortedByDescending { it.photoIds.size }.forEach { folder ->
            if (folder.photoIds.isEmpty()) return@forEach
            val members = membersByFolder.getValue(folder.folderId)
            val folderPicks = MmrSelector.selectInFolder(score, world.embeddings, members, burstIds, quota.getValue(folder.folderId))
            perFolder["${folder.conceptName}›${folder.name}"] = folderPicks.size
            folderPicks.forEach { pick ->
                val i = pick.index
                val row = rows[i]
                val breakdown = linkedMapOf<String, Any?>(
                    "pipeline" to "v3", "score" to round4(score[i]),
                    "prior_z" to round3(combined.priorZ[i]),
                    "balance_z" to round3(combined.balanceZ[i]), "affinity_z" to round3(combined.affinityZ[i]),
                    "technical_pct" to round1(row.technicalPct), "aesthetic_pct" to round1(row.aestheticPct),
                    "burst_id" to row.burstId,   // [GLOSSARY-2 2026-09-27] 근거 키 cluster_id → burst_id (V23이 기존 행도 옮긴다)
                    "folder" to "${folder.conceptName}›${folder.name}", "folder_size" to folder.photoIds.size,
                    "folder_rank" to pick.folderRank, "folder_quota" to pick.quota,
                )
                if (prefOn) breakdown["subjects"] = row.subjects

                picks += PlannedPick(
                    photoId = row.photoId,
                    folderId = folder.folderId,
                    rank = pick.folderRank,
                    breakdown = breakdown,
                )
            }
        }

        return Draft(
            picks = picks,
            perFolder = perFolder,
            folders = folders.count { it.photoIds.isNotEmpty() },
            unfiled = unfiled.size,
            selected = nSelected,
            remaining = remaining,
            preferenceOn = prefOn,
            misfitPhotoIds = misfitPhotoIds,
        )
    }

    // ── 적재 ──

    private fun persistRound(world: World, draft: Draft): Int {
        val job = aiSelectionJobRepository.findById(world.job.id).orElseThrow()
        val roundNo = if (world.job.mode == AiSelectionMode.DRAFT) 1 else world.previousRound + 1
        val now = ZonedDateTime.now(clock)
        // 리셋 — 이 잡의 범위에 든 사진의 기존 추천을 지운다. 폴더 범위면 그 폴더의 지금 사진, 전체면 분석된
        // 사진 전부다. 범위 밖 사진(다른 폴더, 옮겨 나간 사진)의 추천은 남고, 거절 행도 남긴다.
        val scopePhotoIds = when (world.scopeFolderIds) {
            null -> world.rows.map { it.photoId }
            else -> world.folders.flatMap { it.photoIds }
        }
        if (scopePhotoIds.isNotEmpty()) {
            aiRecommendationRepository.deleteCurrentBySelectionIdAndPhotoIdIn(world.selectionId, scopePhotoIds)
        }
        aiRecommendationRepository.saveAll(
            draft.picks.map { pick ->
                AiRecommendation(
                    selectionId = world.selectionId,
                    photoId = pick.photoId,
                    round = roundNo,
                    rank = pick.rank,
                    presentedAt = now,
                    folderId = pick.folderId,
                    scoreBreakdown = pick.breakdown,
                )
            },
        )
        job.assignRound(roundNo)
        return roundNo
    }

    private fun summary(world: World, draft: Draft, roundNo: Int, timing: StageTiming): Map<String, Any?> {
        return linkedMapOf(
            "gallery" to world.galleryId, "pipeline" to "v3", "round" to roundNo, "done" to false, "k" to draft.picks.size,
            "selected" to draft.selected, "target" to world.target, "remaining" to draft.remaining,
            "folders" to draft.folders, "unfiled" to draft.unfiled, "scopeFolderId" to world.scopeFolderIds?.singleOrNull(),
            "scopeDetailFolderIds" to world.scopeFolderIds, "requestedCount" to world.requestedCount,
            "shortfallCount" to world.requestedCount?.let { (it - draft.picks.size).coerceAtLeast(0) },
            "perFolder" to draft.perFolder,
            "misfit" to draft.misfitPhotoIds.size, "misfitPhotoIds" to draft.misfitPhotoIds,
            "preferenceOn" to draft.preferenceOn,
            "elapsedSeconds" to timing.elapsedSeconds(),
            "timing" to timing.toMap(),
        )
    }

    private fun normalized(vector: FloatArray): FloatArray {
        var sum = 0.0
        vector.forEach { sum += it * it }
        val norm = sqrt(sum)
        if (norm < 1e-12) return vector
        return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }

    private fun round1(v: Double) = round(v * 10) / 10
    private fun round3(v: Double) = round(v * 1000) / 1000
    private fun round4(v: Double) = round(v * 10000) / 10000

    private class JobRef(val id: Long, val mode: AiSelectionMode, val round: Int?)

    private data class SavedRound(
        val jobId: Long,
        val galleryId: Long,
        val round: Int,
        val requestedCount: Int?,
        val scopeFolderIds: List<Long>?,
        /** 거절되지 않은 추천마다 추천 당시 폴더 이름. 잡 요약의 장수·폴더별 수를 다시 센다. */
        val folderOfPicks: List<String>,
        val summary: Map<String, Any?>,
    )

    /**
     * 단계별 소요를 잡 result에 남기기 위한 초시계. 운영에서 "어디가 느린가"를 로그 시각을 맞춰 보지 않고
     * result 하나로 읽게 한다 — 읽기(load)·계산(plan)·적재(persist).
     */
    private class StageTiming {
        private val startedNanos = System.nanoTime()
        private val stages = linkedMapOf<String, Double>()

        fun <T> measure(stage: String, block: () -> T): T {
            val started = System.nanoTime()
            try {
                return block()
            } finally {
                stages[stage] = seconds(started)
            }
        }

        fun elapsedSeconds() = seconds(startedNanos)

        fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>("stagesSeconds" to stages.toMap())

        private fun seconds(sinceNanos: Long) = round(Duration.ofNanos(System.nanoTime() - sinceNanos).toMillis() / 10.0) / 100
    }

    // [GLOSSARY-1 2026-09-27] parentName → conceptName (용어집: 1층 이름은 concept_name, 층 뜻의 parent는 금지어).
    private class VirtualFolder(val folderId: Long?, val conceptName: String, val name: String, val photoIds: List<Long>)

    private class World(
        val job: JobRef,
        val selectionId: Long,
        val galleryId: Long,
        val rows: List<RecommendablePhotoDto>,
        val embeddings: Array<FloatArray>,
        val folders: List<FolderSetDetailDto>,
        /** 폴더별 몫(쿼터)을 정할 때 쓰는 세트 전체. 전체 라운드면 folders와 같다. */
        val quotaFolders: List<FolderSetDetailDto>,
        /** 범위 세부폴더. null이면 전체 라운드. */
        val scopeFolderIds: List<Long>?,
        val requestedCount: Int?,
        val selected: Set<Long>,
        val rejected: Set<Long>,
        val target: Int,
        val previousRound: Int,
    )

    private class PlannedPick(
        val photoId: Long,
        val folderId: Long?,
        val rank: Int,
        val breakdown: Map<String, Any?>,
    )

    private class Draft(
        val picks: List<PlannedPick>,
        val perFolder: Map<String, Int>,
        val folders: Int,
        val unfiled: Int,
        val selected: Int,
        val remaining: Int,
        val preferenceOn: Boolean,
        /** 폴더와 동떨어져 후보에서 뺀 사진. 잡 result에 남긴다. */
        val misfitPhotoIds: List<Long>,
    )

    companion object {
        /** 목표 장수 폴백 — galleries.max_selectable_photo_count가 없을 때. */
        const val DEFAULT_TARGET = 30
        private const val SUBJECTS_TRUSTED = true
        private const val PREF_MIN_SELECTED = 5
        private const val UNFILED = "미분류"
        private const val UNKNOWN = "unknown"

        /** 범위 밖 행의 벡터 자리. 조회 조건이 벡터 있는 행만 고르므로 범위 안 사진에는 오지 않는다. */
        private val NO_EMBEDDING = FloatArray(0)
    }
}

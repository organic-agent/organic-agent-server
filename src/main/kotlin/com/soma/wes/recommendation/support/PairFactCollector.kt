package com.soma.wes.recommendation.support

import com.soma.wes.recommendation.dto.ComparablePhotoDto
import com.soma.wes.recommendation.dto.PairFactsDto
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * 두 사진의 측정된 사실을 문장으로 만든다. AI repo `compare.verdict.collect_facts`의 자리다.
 *
 * 문장에는 내부 라벨 a/b를 쓴다 — LLM은 이 라벨로 고르고, 사용자 문장에는 절대 쓰지 않도록
 * 프롬프트가 막는다. 백분위 차는 [PCT_GAP] 이상일 때만 근거가 된다(§3.1).
 */
object PairFactCollector {

    /** 선명도 비가 이보다 커야 "더 또렷"이라고 말한다 (추천의 `whyNot`과 같은 기준). */
    const val SHARPNESS_RATIO = 1.25

    /** 백분위 차가 이보다 작으면 근거로 쓰지 않는다. */
    const val PCT_GAP = 5.0

    fun collect(
        a: ComparablePhotoDto,
        b: ComparablePhotoDto,
        folderA: String?,
        folderB: String?,
        selected: Set<Long>,
    ): PairFactsDto {
        val sentences = mutableListOf<String>()
        val facts = linkedMapOf<String, Any?>()

        val sa = a.sharpness ?: 0.0
        val sb = b.sharpness ?: 0.0
        if (sa > 0 && sb > 0) {
            val ratio = if (sa >= sb) sa / sb else sb / sa
            if (ratio >= SHARPNESS_RATIO) {
                val lead = if (sa >= sb) "a" else "b"
                sentences += "초점: 사진 $lead 가 더 또렷하다 (선명도 ${fmt(ratio, 1)}배)"
                facts["sharper"] = mapOf("photo" to lead, "ratio" to round2(ratio))
            }
        }

        listOf(
            Triple("기술(화질)", "technical_pct", a.technicalPct to b.technicalPct),
            Triple("미학(인상)", "aesthetic_pct", a.aestheticPct to b.aestheticPct),
        ).forEach { (name, key, values) ->
            val (va, vb) = values
            val gap = va - vb
            if (abs(gap) >= PCT_GAP) {
                val lead = if (gap > 0) "a" else "b"
                sentences += "$name 백분위: 사진 $lead 가 ${fmt(abs(gap), 0)}포인트 높다 " +
                    "(a ${fmt(va, 0)} / b ${fmt(vb, 0)}, 갤러리 안 순위)"
                facts[key] = mapOf("a" to round1(va), "b" to round1(vb))
            }
        }

        listOf("a" to a, "b" to b).forEach { (label, photo) ->
            if ((photo.highlightClip ?: 0.0) >= HIGHLIGHT_CLIP_MIN) {
                sentences += "노출: 사진 $label 는 밝은 부분이 일부 날아갔다"
                exposure(facts)[label] = "highlight_clip"
            }
        }

        val clusterA = a.clusterId
        if (clusterA != null && clusterA == b.clusterId && clusterA >= 0) {
            val best = if ((a.clusterRank ?: 0) <= (b.clusterRank ?: 0)) "a" else "b"
            sentences += "연사: 같은 순간에 찍힌 연속 촬영 컷이다. 점수 기준 대표는 사진 $best"
            facts["same_burst"] = mapOf("best" to best)
        }

        if (folderA != null || folderB != null) {
            sentences += if (folderA == folderB) {
                "폴더: 두 장 다 \"$folderA\" 폴더다"
            } else {
                "폴더: 사진 a 는 \"${folderA ?: UNFILED}\", 사진 b 는 \"${folderB ?: UNFILED}\""
            }
            facts["folders"] = mapOf("a" to folderA, "b" to folderB)
        }

        if (a.subjects != b.subjects && a.subjects != UNKNOWN_SUBJECT && b.subjects != UNKNOWN_SUBJECT) {
            sentences += "유형: 사진 a 는 ${a.subjects}, 사진 b 는 ${b.subjects} — 담는 용도가 다를 수 있다"
            facts["subjects"] = mapOf("a" to a.subjects, "b" to b.subjects)
        }

        listOf("a" to a, "b" to b).forEach { (label, photo) ->
            if (photo.photoId in selected) {
                sentences += "참고: 사진 $label 는 이미 담은 사진이다"
                alreadySelected(facts) += label
            }
        }

        if (sentences.isEmpty()) {
            sentences += "측정된 차이 없음 — 두 장의 수치가 거의 같다. 사진에서 보이는 것으로 판단하라"
        }
        return PairFactsDto(sentences, facts)
    }

    @Suppress("UNCHECKED_CAST")
    private fun exposure(facts: MutableMap<String, Any?>): MutableMap<String, String> =
        facts.getOrPut("exposure") { linkedMapOf<String, String>() } as MutableMap<String, String>

    @Suppress("UNCHECKED_CAST")
    private fun alreadySelected(facts: MutableMap<String, Any?>): MutableList<String> =
        facts.getOrPut("already_selected") { mutableListOf<String>() } as MutableList<String>

    private fun fmt(value: Double, digits: Int): String = String.format(Locale.ROOT, "%.${digits}f", value)

    private fun round1(value: Double): Double = round(value * 10) / 10

    private fun round2(value: Double): Double = round(value * 100) / 100

    private const val HIGHLIGHT_CLIP_MIN = 0.01
    private const val UNFILED = "미분류"
    private const val UNKNOWN_SUBJECT = "unknown"
}

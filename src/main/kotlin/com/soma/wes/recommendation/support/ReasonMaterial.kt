package com.soma.wes.recommendation.support

import com.soma.wes.recommendation.dto.RecommendablePhotoDto
import kotlin.math.roundToInt

/**
 * 이유 문장의 재료 — AI repo `draft._why_not`·`_quality_material`, `reasons.facts_of`·`template`의 자리다.
 * 재료(material)는 키가 정해진 Map이고, 여기 없는 것은 LLM도 모른다. 숫자는 전부 여기서 나온다.
 */
object ReasonMaterial {

    val REASON_PRIORITY = listOf("balance", "quality", "sibling", "folder", "score", "diversity")

    /** 근거: 백분위를 말해도 되는 상한(상위 15%). */
    const val QUALITY_TOP_PCT = 15.0

    /** 선명도 비가 이보다 커야 "더 또렷"이라고 말한다. */
    const val SHARPNESS_RATIO = 1.25

    /** 백분위 차가 이보다 작으면 근거로 쓰지 않는다. */
    const val PCT_GAP = 5.0

    /** 절대 품질 하한 게이트 — 원점수가 하한 미만이면 품질 표현만 근거에서 뺀다. 사진을 거르지는 않는다. */
    const val QUALITY_FLOOR_TECHNICAL = 0.35
    const val QUALITY_FLOOR_AESTHETIC = 4.5

    val SUBJECT_KO = mapOf("bride" to "신부 단독", "groom" to "신랑 단독", "couple" to "두 분", "group" to "단체", "unknown" to "")

    /** 형제(연사) 컷이 밀린 이유 한 마디. [SHARPNESS_RATIO]·[PCT_GAP]이 기준이다. */
    fun whyNot(pick: RecommendablePhotoDto, other: RecommendablePhotoDto): String {
        val ps = pick.subScore("sharpness") ?: 0.0
        val os = other.subScore("sharpness") ?: 0.0
        if (ps > SHARPNESS_RATIO * os && ps > 0) return "덜 선명함"
        if (pick.technicalPct - other.technicalPct > PCT_GAP) return "화질이 떨어짐"
        if (pick.aestheticPct - other.aestheticPct > PCT_GAP) return "인상이 약함"
        return "거의 같은 컷"
    }

    /**
     * 백분위 상위면 품질 재료. 단 원점수가 절대 하한 미만이면 null — 백분위가 절대 품질을 소거하는 문제.
     * 소거는 근거 문장에서만이고 후보에서는 아니다.
     */
    fun qualityMaterial(row: RecommendablePhotoDto): Map<String, Any?>? {
        val technicalScore = row.subScore("technical_score")
        val aestheticScore = row.subScore("aesthetic_score")
        if ((technicalScore != null && technicalScore < QUALITY_FLOOR_TECHNICAL) ||
            (aestheticScore != null && aestheticScore < QUALITY_FLOOR_AESTHETIC)
        ) return null
        val top = 100 - QUALITY_TOP_PCT
        val material = linkedMapOf<String, Any?>()
        if (row.aestheticPct >= top) material["aesthetic_top"] = topPct(row.aestheticPct)
        if (row.technicalPct >= top) material["technical_top"] = topPct(row.technicalPct)
        if (material.isEmpty()) return null
        val descriptors = mutableListOf<String>()
        if ((row.subScore("sharpness_pct") ?: 0.0) >= top) descriptors += "초점이 또렷함"
        if ((row.subScore("highlight_clip") ?: 1.0) < 0.01 && (row.subScore("shadow_clip") ?: 1.0) < 0.02) descriptors += "노출이 안정적"
        material["descriptors"] = descriptors
        return material
    }

    fun topPct(pct: Double): Int = maxOf(1, (100 - pct).roundToInt())

    /** (주 사유, 사실 문장들). LLM에 주는 재료 — 여기 없는 건 LLM도 모른다. */
    fun factsOf(material: Map<String, Any?>): Pair<String, List<String>> {
        val facts = linkedMapOf<String, String>()
        material.map("balance")?.let { m ->
            facts["balance"] = "균형: 담은 ${m["total_sel"]}장 중 ${m["label"]} ${m["sel"]}장 (갤러리 비율대로면 ${m["expected"]}장) — 부족"
        }
        material.map("sibling")?.let { m ->
            facts["sibling"] = "형제: 같은 순간 연사 ${m["n"]}장 중 이 컷. 나머지는 ${whyCounts(m)}"
        }
        material.map("quality")?.let { m ->
            val q = mutableListOf<String>()
            m["aesthetic_top"]?.let { q += "미학 상위 $it%" }
            m["technical_top"]?.let { q += "기술 상위 $it%" }
            @Suppress("UNCHECKED_CAST")
            q += (m["descriptors"] as? List<String>).orEmpty()
            facts["quality"] = "품질: " + q.joinToString(" · ") + " (갤러리 안 순위)"
        }
        val folder = material.map("folder")
        val folderTop = folder?.get("rank") == 1
        if (folder != null) {
            facts["folder"] = "폴더: ${folderClause(folder)} (추천 ${folder["quota"]}장)"
        }
        val priorZ = (material["prior_z"] as? Number)?.toDouble() ?: 0.0
        if (priorZ > 1.0 && "quality" !in facts) {
            facts["score"] = "점수: 기술·미학 종합이 갤러리 평균보다 뚜렷이 높음"
        }
        // 주 사유 후보: folder는 폴더 1위일 때만. 전부 탈락하면 diversity(MMR이 끌어올린 컷).
        val eligible = facts.keys.filter { it != "folder" || folderTop }
        val primary = REASON_PRIORITY.firstOrNull { it in eligible } ?: "diversity"
        if (primary == "diversity") {
            facts["diversity"] = "다양성: 앞서 고른 사진들과 배경·구도가 겹치지 않아 뽑힘 (점수는 평범)"
        }
        return primary to REASON_PRIORITY.mapNotNull { facts[it] }
    }

    /** LLM 없이도 납득되는 한 문장. */
    fun template(primary: String, material: Map<String, Any?>): String {
        val sibling = material.map("sibling")
        return when (primary) {
            "balance" -> material.map("balance")!!.let { m ->
                "담으신 사진에 ${m["label"]} 컷이 부족해요 (${m["sel"]}장/${m["total_sel"]}장), 이 컷으로 채워요"
            }
            "quality" -> material.map("quality")!!.let { m ->
                val parts = mutableListOf<String>()
                m["aesthetic_top"]?.let { parts += "인상 상위 $it%" }
                m["technical_top"]?.let { parts += "화질 상위 $it%" }
                val s = "전체 중 " + parts.joinToString("·")
                if (sibling != null) {
                    "$s, ${siblingClause(sibling)}이에요"
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val extra = (m["descriptors"] as? List<String>).orEmpty().joinToString(" · ")
                    if (extra.isNotEmpty()) "${s}인 컷이에요, $extra" else "${s}인 컷이에요"
                }
            }
            "sibling" -> "${siblingClause(sibling!!)}이에요, 나머지는 ${whyCounts(sibling)}"
            "folder" -> "${folderClause(material.map("folder")!!)}인 컷이에요"
            "score" -> "기술·미학 점수가 갤러리 평균보다 뚜렷이 높은 컷이에요"
            else -> "앞서 고른 사진들과 배경·구도가 겹치지 않아 고른 컷이에요"
        }
    }

    private fun siblingClause(m: Map<String, Any?>): String {
        val lead = if (m["sharpest"] == true) "초점이 가장 또렷한" else "점수가 가장 높은"
        return "연사 ${m["n"]}장 중 $lead 컷"
    }

    private fun folderClause(m: Map<String, Any?>): String =
        "\"${m["parent"]} › ${m["name"]}\" 폴더 ${m["size"]}장 중 ${m["rank"]}위"

    @Suppress("UNCHECKED_CAST")
    private fun whyCounts(m: Map<String, Any?>): String =
        (m["why_counts"] as? Map<String, Any?>).orEmpty().entries.joinToString(" · ") { "${it.key} ${it.value}" }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.map(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
}

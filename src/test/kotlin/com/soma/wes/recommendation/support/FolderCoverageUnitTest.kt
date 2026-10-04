package com.soma.wes.recommendation.support

import com.soma.wes.recommendation.dto.RecommendablePhotoDto
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.math.sqrt

class FolderCoverageUnitTest {

    @Nested
    @DisplayName("장수 상한을 정할 때")
    inner class Cap {

        @Test
        fun `폴더 사진의 10퍼센트를 올림하고 최소 1장, 최대 골라야 하는 장수다`() {
            assertSoftly { softly ->
                softly.assertThat(FolderCoverage.cap(folderSize = 1048, selectable = 30)).isEqualTo(30)
                softly.assertThat(FolderCoverage.cap(folderSize = 116, selectable = 30)).isEqualTo(12)
                softly.assertThat(FolderCoverage.cap(folderSize = 13, selectable = 30)).isEqualTo(2)
                softly.assertThat(FolderCoverage.cap(folderSize = 5, selectable = 30)).isEqualTo(1)
                softly.assertThat(FolderCoverage.cap(folderSize = 300, selectable = 20)).isEqualTo(20)
            }
        }
    }

    @Nested
    @DisplayName("연사를 조각으로 나눌 때")
    inner class Pieces {

        @Test
        fun `연사를 촬영 순서로 걸으며 첫 컷에서 멀어진 곳마다 새 조각을 연다`() {
            // given — 한 연사 45장, 촬영 순서로 15장씩 세 방향(장면)이 이어진다
            val rows = (0 until 45).map { row(photoId = 100L + it, burstId = 7, takenAt = BASE.plusSeconds(it.toLong())) }
            val emb = Array(45) { axis(it / 15) }

            // when
            val pieces = FolderCoverage.pieces(rows.indices.toList(), rows, emb)

            // then
            assertThat(pieces).containsExactly((0 until 15).toList(), (15 until 30).toList(), (30 until 45).toList())
        }

        @Test
        fun `같은 장면 연사는 촬영 순서대로 한 조각이고 연사 번호가 없는 사진은 한 장씩이다`() {
            // given — 대표 순위(burstRank)는 촬영 순서와 반대다
            val rows = listOf(
                row(photoId = 1, burstId = 3, burstRank = 0, takenAt = BASE.plusSeconds(5)),
                row(photoId = 2, burstId = 3, burstRank = 1, takenAt = BASE),
                row(photoId = 3, burstId = -1),
                row(photoId = 4, burstId = -1),
            )
            val emb = Array(4) { axis(0) }

            // when
            val pieces = FolderCoverage.pieces(rows.indices.toList(), rows, emb)

            // then — 연사 안은 대표 순위가 아니라 촬영 시각 순
            assertThat(pieces).containsExactly(listOf(1, 0), listOf(2), listOf(3))
        }
    }

    @Nested
    @DisplayName("연사가 길어도")
    inner class LongBurst {

        @Test
        fun `장면이 그대로면 60장도 한 조각이다`() {
            // given — 거의 같은 사진 60장(장수가 많다고 쪼개지 않는다)
            val rows = (0 until 60).map { row(photoId = 200L + it, burstId = 9, takenAt = BASE.plusSeconds(it.toLong())) }
            val emb = Array(60) { axis(0) }

            // when
            val pieces = FolderCoverage.pieces(rows.indices.toList(), rows, emb)

            // then
            assertThat(pieces).hasSize(1)
        }
    }

    @Nested
    @DisplayName("폴더 추천을 고를 때")
    inner class Select {

        @Test
        fun `연사마다 점수 최고 컷 한 장을 점수순으로 낸다`() {
            // given — 연사 1(0·1), 연사 2(2·3)
            val rows = listOf(row(1, burstId = 1), row(2, burstId = 1), row(3, burstId = 2), row(4, burstId = 2))
            val score = doubleArrayOf(0.2, 0.9, 0.5, 0.4)

            // when
            val result = select(rows, score, n = 10)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.picks).containsExactly(1, 2)
                softly.assertThat(result.pieces).isEqualTo(2)
            }
        }

        @Test
        fun `담은 사진이 든 연사는 통째로 빠지고 제외 사진은 대표가 되지 못한다`() {
            // given — 연사 1에서 하나를 담았고, 연사 2의 최고점(3)은 거절됐다
            val rows = listOf(row(1, burstId = 1), row(2, burstId = 1), row(3, burstId = 2), row(4, burstId = 2))
            val score = doubleArrayOf(0.2, 0.9, 0.8, 0.4)

            // when
            val result = select(rows, score, n = 10, selected = setOf(0), excluded = setOf(2))

            // then
            assertSoftly { softly ->
                softly.assertThat(result.picks).containsExactly(3)
                softly.assertThat(result.skippedPieces).isEqualTo(1)
            }
        }

        @Test
        fun `상한이 0이면 후보가 있어도 아무것도 내지 않는다`() {
            // given — 골라야 하는 장수가 0인 갤러리(상한 0)
            val rows = (0 until 3).map { row(photoId = 30L + it, burstId = it) }
            val score = doubleArrayOf(0.9, 0.5, 0.1)

            // when
            val result = select(rows, score, n = 0)

            // then
            assertSoftly { softly ->
                softly.assertThat(FolderCoverage.cap(folderSize = 300, selectable = 0)).isZero()
                softly.assertThat(result.picks).isEmpty()
                softly.assertThat(result.pieces).isEqualTo(3)
            }
        }

        @Test
        fun `다른 연사라도 같은 컷으로 보이는 후보는 점수 높은 하나만 남긴다`() {
            // given — 연사 1·2의 대표가 같은 방향(거리 0)
            val rows = listOf(row(1, burstId = 1), row(2, burstId = 2), row(3, burstId = 3))
            val emb = arrayOf(axis(0), axis(0), axis(1))
            val score = doubleArrayOf(0.5, 0.9, 0.1)

            // when
            val result = FolderCoverage.select(rows.indices.toList(), emptySet(), emptySet(), rows, score, emb, n = 10)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.picks).containsExactly(1, 2)
                softly.assertThat(result.nearDuplicates).isEqualTo(1)
            }
        }

        @Test
        fun `후보가 상한보다 많으면 점수 상위가 아니라 장면마다 한 장씩 고루 고른다`() {
            // given — 한 장짜리 연사 6개가 세 장면에 두 개씩(같은 장면 안 거리 0.1 — 같은 컷은 아니다).
            // 점수 상위 셋 중 둘이 같은 장면(장면 0)이다
            val rows = (0 until 6).map { row(photoId = 10L + it, burstId = it) }
            val emb = Array(6) { tilted(scene = it / 2, side = it) }
            val score = doubleArrayOf(0.9, 0.8, 0.3, 0.2, 0.7, 0.1)

            // when
            val result = FolderCoverage.select(rows.indices.toList(), emptySet(), emptySet(), rows, score, emb, n = 3)

            // then — 장면마다 최고점: 0(0.9) · 4(0.7) · 2(0.3)
            assertThat(result.picks).containsExactly(0, 4, 2)
        }

        @Test
        fun `같은 입력이면 사진 순서가 달라도 같은 결과이고 동점은 photoId 순이다`() {
            // given
            val rows = (0 until 8).map { row(photoId = 50L - it, burstId = it) }
            val emb = Array(8) { axis(it % 4) }
            val score = DoubleArray(8) { 0.5 }

            // when
            val forward = FolderCoverage.select(rows.indices.toList(), emptySet(), emptySet(), rows, score, emb, n = 4)
            val reversed = FolderCoverage.select(rows.indices.reversed().toList(), emptySet(), emptySet(), rows, score, emb, n = 4)

            // then
            assertSoftly { softly ->
                softly.assertThat(forward.picks).isEqualTo(reversed.picks)
                softly.assertThat(forward.picks.map { rows[it].photoId }).isSorted()
            }
        }
    }

    private fun select(
        rows: List<RecommendablePhotoDto>,
        score: DoubleArray,
        n: Int,
        selected: Set<Int> = emptySet(),
        excluded: Set<Int> = emptySet(),
    ) = FolderCoverage.select(rows.indices.toList(), selected, excluded, rows, score, Array(rows.size) { axis(rows[it].burstId) }, n)

    private fun row(photoId: Long, burstId: Int, burstRank: Int = 0, takenAt: LocalDateTime? = null) = RecommendablePhotoDto(
        photoId = photoId,
        technicalPct = 50.0,
        aestheticPct = 50.0,
        subjects = "couple",
        burstId = burstId,
        burstRank = burstRank,
        subScores = emptyMap(),
        takenAt = takenAt,
    )

    /** 장면 축에서 조금 기운 단위 벡터. 같은 장면끼리 거리 = sin²(θ) = 0.1, 다른 장면과는 거의 1. */
    private fun tilted(scene: Int, side: Int): FloatArray = FloatArray(DIMENSION).also {
        it[scene] = sqrt(0.9).toFloat()
        it[8 + side] = sqrt(0.1).toFloat()
    }

    /** 축 하나만 1인 단위 벡터 — 축이 다르면 직교(거리 1), 같으면 같은 방향(거리 0). */
    private fun axis(i: Int): FloatArray = FloatArray(DIMENSION).also { it[i % DIMENSION] = 1f }

    companion object {
        private const val DIMENSION = 16
        private val BASE: LocalDateTime = LocalDateTime.of(2026, 5, 2, 14, 0)
    }
}

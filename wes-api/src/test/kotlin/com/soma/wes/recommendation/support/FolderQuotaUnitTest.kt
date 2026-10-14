package com.soma.wes.recommendation.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FolderQuotaUnitTest {

    @Test
    fun `목표에 비례하되 폴더당 최소 1장·절반 상한이다`() {
        // given — 10장·2장 폴더에 목표 4장
        val sizes = mapOf<Long?, Int>(1L to 10, 2L to 2)

        // when
        val quota = FolderQuota.quota(sizes, target = 4)

        // then — 10장 폴더 round(4·10/12)=3, 2장 폴더 max(1, round(0.67))=1
        assertThat(quota).containsEntry(1L, 3).containsEntry(2L, 1)
    }

    @Test
    fun `목표를 채운 뒤(remaining 0 이하)에도 폴더마다 1장은 남기고, 1장짜리 폴더는 상한이 1이다`() {
        // given
        val sizes = mapOf<Long?, Int>(1L to 6, null to 1)

        // when
        val exhausted = FolderQuota.quota(sizes, target = -3)
        val generous = FolderQuota.quota(sizes, target = 100)

        // then
        assertThat(exhausted).containsEntry(1L, 1).containsEntry(null, 1)
        assertThat(generous).containsEntry(1L, 3).containsEntry(null, 1)
    }
}

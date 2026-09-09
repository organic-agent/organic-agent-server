package com.soma.wes.recommendation.service

import com.soma.wes.category.dto.FolderSetDetailDto
import com.soma.wes.recommendation.domain.ResolvedRecommendationQuery
import com.soma.wes.recommendation.exception.RecommendationErrorCode
import com.soma.wes.recommendation.exception.RecommendationException
import com.soma.wes.support.FakeStructuredLlmClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RecommendationQueryInterpreterTest {
    private val llm = FakeStructuredLlmClient()
    private val interpreter = RecommendationQueryInterpreter(llm)
    private val folders = listOf(
        FolderSetDetailDto(11, "가든 야외", "산책", listOf(1)),
        FolderSetDetailDto(12, "가든 야외", "정면", listOf(2)),
        FolderSetDetailDto(13, "스튜디오", "전신", listOf(3)),
    )

    @Test
    fun `자연어가 컨셉의 여러 폴더와 장수로 해석된다`() {
        llm.respondWith("""{"status":"RESOLVED","scope":"FOLDERS","detailFolderIds":["11","12"],"targetCount":10}""")
        assertThat(interpreter.interpret("가든 사진 중에 10장만 골라줘", null, null, folders))
            .isEqualTo(ResolvedRecommendationQuery(listOf(11, 12), 10))
        assertThat(llm.calls).isEqualTo(1)
    }

    @Test
    fun `명시한 장수가 문장의 장수보다 우선한다`() {
        llm.respondWith("""{"status":"RESOLVED","scope":"ALL","detailFolderIds":[],"targetCount":10}""")
        assertThat(interpreter.interpret("10장 골라줘", 3, null, folders).targetCount).isEqualTo(3)
    }

    @Test
    fun `직접 지정은 AI가 꺼져 있어도 동작한다`() {
        llm.isEnabled = false
        assertThat(interpreter.interpret(null, 2, 11, folders))
            .isEqualTo(ResolvedRecommendationQuery(listOf(11), 2))
        assertThat(llm.calls).isZero()
    }

    @Test
    fun `다른 갤러리 ID와 중복 ID와 잘못된 장수는 거절한다`() {
        listOf(
            """{"status":"RESOLVED","scope":"FOLDERS","detailFolderIds":["999"],"targetCount":2}""",
            """{"status":"RESOLVED","scope":"FOLDERS","detailFolderIds":["11","11"],"targetCount":2}""",
            """{"status":"RESOLVED","scope":"FOLDERS","detailFolderIds":[],"targetCount":2}""",
            """{"status":"RESOLVED","scope":"ALL","detailFolderIds":["11"],"targetCount":2}""",
            """{"status":"RESOLVED","scope":"ALL","detailFolderIds":[],"targetCount":501}""",
            """{"status":"RESOLVED","scope":"ALL","detailFolderIds":[],"targetCount":1.5}""",
        ).forEach { json ->
            llm.respondWith(json)
            assertThatThrownBy { interpreter.interpret("추천해줘", null, null, folders) }
                .isInstanceOf(RecommendationException::class.java)
                .extracting("errorCode").isEqualTo(RecommendationErrorCode.QUERY_NOT_UNDERSTOOD)
        }
    }

    @Test
    fun `직접 지정한 폴더 밖으로 자연어가 범위를 넓힐 수 없다`() {
        llm.respondWith("""{"status":"RESOLVED","scope":"FOLDERS","detailFolderIds":["12"],"targetCount":2}""")
        assertThatThrownBy { interpreter.interpret("다른 폴더 2장", null, 11, folders) }
            .extracting("errorCode").isEqualTo(RecommendationErrorCode.QUERY_NOT_UNDERSTOOD)
    }

    @Test
    fun `지원하지 않는 시각 조건을 조용히 버리지 않는다`() {
        llm.respondWith("""{"status":"UNSUPPORTED","scope":"ALL","detailFolderIds":[],"targetCount":null}""")
        assertThatThrownBy { interpreter.interpret("웃는 얼굴만 골라줘", null, null, folders) }
            .extracting("errorCode").isEqualTo(RecommendationErrorCode.QUERY_NOT_UNDERSTOOD)
    }

    @Test
    fun `AI 비활성은 일반 추천으로 폴백하지 않는다`() {
        llm.isEnabled = false
        assertThatThrownBy { interpreter.interpret("가든 2장", null, null, folders) }
            .extracting("errorCode").isEqualTo(RecommendationErrorCode.QUERY_AI_UNAVAILABLE)
    }
}

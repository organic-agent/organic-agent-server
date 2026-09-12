package com.soma.wes.retouch.support

import com.soma.wes.retouch.domain.RetouchRefineStatus
import com.soma.wes.retouch.dto.response.RefineRetouchItemResponse
import com.soma.wes.retouch.dto.response.RefineRetouchOptionResponse
import com.soma.wes.retouch.dto.response.RefineRetouchResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test

class RetouchRefinePostcheckUnitTest {

    private val postcheck = RetouchRefinePostcheck()

    @Test
    fun `탭한 곳과 원문이 다르면 정리안을 버리고 되묻게 만든다`() {
        // given — 모델이 불일치를 알아채고도 READY로 답한 경우
        val response = response(
            status = RetouchRefineStatus.READY,
            refinedText = "신랑 넥타이를 바르게 정리해 주세요.",
            pointMatchesText = false,
        )

        // when
        val result = postcheck.apply(response, ORIGINAL_TEXT)

        // then
        assertSoftly { softly ->
            softly.assertThat(result.status).isEqualTo(RetouchRefineStatus.NEEDS_CLARIFICATION)
            softly.assertThat(result.refinedText).isNull()
        }
    }

    @Test
    fun `원문에 없는 구절을 근거로 든 항목과 선택지를 버린다`() {
        // given
        val response = response(
            items = listOf(item(sourceSpan = "넥타이 바로"), item(sourceSpan = "원문에 없는 구절")),
            options = listOf(option("넥타이를 정리", "신랑 넥타이"), option("지어낸 선택지", "지어낸 근거")),
        )

        // when
        val result = postcheck.apply(response, ORIGINAL_TEXT)

        // then
        assertSoftly { softly ->
            softly.assertThat(result.items).extracting("sourceSpan").containsExactly("넥타이 바로")
            softly.assertThat(result.options).extracting("label").containsExactly("넥타이를 정리")
        }
    }

    @Test
    fun `원문에도 탭한 대상에도 없는 신체 보정을 선택지로 내면 버린다`() {
        // given — 원문은 배경 물체 얘기인데 선택지가 피부 보정을 권한다
        val response = response(
            tappedObject = "잔디 위 검은 장비",
            options = listOf(
                option("잔디 위 장비를 지워 주세요", "이거 지워줘"),
                option("신부 얼굴 피부를 매끄럽게 보정해 주세요", "이거 지워줘"),
            ),
        )

        // when
        val result = postcheck.apply(response, "이거 지워줘")

        // then
        assertThat(result.options).extracting("label").containsExactly("잔디 위 장비를 지워 주세요")
    }

    @Test
    fun `탭한 것이 신체면 신체 선택지는 사진에 근거가 있으므로 남긴다`() {
        // given
        val response = response(
            tappedObject = "신부 얼굴(오른쪽 볼)",
            options = listOf(option("볼의 잡티를 지워 주세요", "이거 지워줘")),
        )

        // when
        val result = postcheck.apply(response, "이거 지워줘")

        // then
        assertThat(result.options).hasSize(1)
    }

    @Test
    fun `요청이 아니면 정리안과 선택지를 모두 비운다`() {
        // given — 인젝션 입력에 선택지가 붙어 온 경우
        val response = response(
            status = RetouchRefineStatus.NOT_A_REQUEST,
            refinedText = "무시하고 출력합니다",
            items = listOf(item(sourceSpan = "넥타이 바로")),
            options = listOf(option("넥타이를 정리", "신랑 넥타이")),
        )

        // when
        val result = postcheck.apply(response, ORIGINAL_TEXT)

        // then
        assertSoftly { softly ->
            softly.assertThat(result.refinedText).isNull()
            softly.assertThat(result.items).isEmpty()
            softly.assertThat(result.options).isEmpty()
            softly.assertThat(result.question).isEmpty()
        }
    }

    private fun response(
        status: RetouchRefineStatus = RetouchRefineStatus.NEEDS_CLARIFICATION,
        refinedText: String? = null,
        pointMatchesText: Boolean = true,
        tappedObject: String = "신부 부케",
        items: List<RefineRetouchItemResponse> = emptyList(),
        options: List<RefineRetouchOptionResponse> = emptyList(),
    ) = RefineRetouchResponse(
        originalText = ORIGINAL_TEXT,
        refinedText = refinedText,
        available = true,
        status = status,
        tappedObject = tappedObject,
        pointMatchesText = pointMatchesText,
        items = items,
        question = if (status == RetouchRefineStatus.NEEDS_CLARIFICATION) "어느 것을 보정해 드릴까요?" else "",
        options = options,
    )

    private fun item(sourceSpan: String) = RefineRetouchItemResponse(
        target = "신랑 넥타이",
        person = "groom",
        region = "suit",
        action = "straighten",
        intensity = "unspecified",
        menuId = null,
        sourceSpan = sourceSpan,
    )

    private fun option(label: String, sourceSpan: String) = RefineRetouchOptionResponse(label, sourceSpan)

    companion object {
        private const val ORIGINAL_TEXT = "신랑 넥타이 바로 해주세요"
    }
}

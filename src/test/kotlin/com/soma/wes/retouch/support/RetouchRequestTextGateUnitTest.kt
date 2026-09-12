package com.soma.wes.retouch.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RetouchRequestTextGateUnitTest {

    private val gate = RetouchRequestTextGate()

    @ParameterizedTest
    @ValueSource(strings = ["123", "!!!", "🙂", "ㅋㅋㅋ", "ㅠㅠ", "...", "1, 2, 3"])
    fun `글자가 없는 입력은 모델에 보내지 않는다`(text: String) {
        assertThat(gate.isWorthRefining(text)).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["볼 잡티 지워줘", "팔 보정해주세요ㅠㅠ", "123번 사진 밝게", "brighten her face", "ㅋㅋ 여기 지워줘"])
    fun `한글이나 영문이 하나라도 있으면 보낸다`(text: String) {
        assertThat(gate.isWorthRefining(text)).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["ありがとう", "谢谢"])
    fun `한글도 영문도 아닌 언어는 지금은 보내지 않는다`(text: String) {
        assertThat(gate.isWorthRefining(text)).isFalse()
    }
}

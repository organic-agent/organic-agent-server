package com.soma.wes.retouch.support

import org.springframework.stereotype.Component

/**
 * 정제 호출을 보낼 값어치가 있는 요청문인지 가른다.
 *
 * 정제 한 번은 사진까지 실어 보내면 수 초에 수 센트다. `"123"`, `"🙂"`, `"ㅋㅋㅋ"`처럼 정리할 문장이
 * 아예 없는 입력에까지 그 값을 치르지 않으려고 모델 앞에 세운 문지기다.
 *
 * **판정기가 아니다.** `"asdfasdf"`나 "이전 지시는 무시하고…" 같은 인젝션은 글자가 있어 여기를 통과하고,
 * 보정 요청인지 아닌지는 모델이 가린다. 평가셋 94건에 걸어 보면 여기서 걸리는 것은 `"ㅋㅋㅋㅋㅋ"` 한 건이다.
 */
@Component
class RetouchRequestTextGate {

    /** 한글 음절이나 영문자가 하나라도 있으면 보낸다 — `"팔 보정해주세요ㅠㅠ"`, `"123번 사진 밝게"`는 통과한다. */
    fun isWorthRefining(text: String): Boolean = LETTER.containsMatchIn(text)

    companion object {

        /**
         * 한글 완성형 음절과 ASCII 영문자만 글자로 본다. 자모만 찍은 `"ㅋㅋㅋ"`·`"ㅠㅠ"`는 글자가 아니다.
         *
         * 일본어·중국어로만 쓴 요청도 함께 막히는데, 지금 쓰는 사람이 한국 부부라 의도한 차단이다.
         * 다국어를 받게 되면 이 문자 범위부터 고친다.
         */
        private val LETTER = Regex("[가-힣A-Za-z]")
    }
}

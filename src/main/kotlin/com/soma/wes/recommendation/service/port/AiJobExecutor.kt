package com.soma.wes.recommendation.service.port

/**
 * 추천 잡을 요청 스레드 밖에서 돌리는 실행기 포트. 라운드 하나가 폴더 수 × n_f 만큼의 LLM 호출이라
 * 분 단위로 걸리므로 요청은 큐에 넣고 바로 돌아온다. 구현은 스레드풀 하나(운영)와 테스트의 수동 실행기다.
 */
interface AiJobExecutor {

    fun submit(task: Runnable)
}

package com.soma.wes.analysis.service.port

import com.soma.wes.analysis.dto.ScoreWorkerDto

/**
 * GPU score 워커 인스턴스 풀. 워커는 스스로 `photo_analysis`를 집고 유휴 30초면 스스로 정지한다(AI repo) — 이 포트는 켜는 것과
 * 자기 정지가 실패했을 때 끄는 것만 맡는다. 운영은 EC2, 로컬은 서브프로세스, 테스트는 Manual 구현.
 */
interface ScoreWorkerPool {

    /** 풀이 실제로 붙어 있는지. 로컬·테스트에는 없는 것이 정상이라 기동을 막지 않는다. */
    val isAvailable: Boolean

    /** 풀의 인스턴스 전부와 지금 상태. */
    fun snapshot(): List<ScoreWorkerDto>

    /** 꺼진 인스턴스 하나를 켠다. 켤 것이 없으면 아무것도 하지 않는다. 호출 자체가 실패하면 도메인 예외를 던진다. */
    fun start()

    fun stop(instanceId: String)
}

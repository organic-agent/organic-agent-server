package com.soma.wes.global.cluster

/**
 * 이 서버가 지금 주기 작업을 돌릴 차례인가(#313 ADR-0002).
 *
 * 서버가 여러 대가 되면 `@Scheduled` 작업은 [isLeader]가 참인 한 대만 돈다. 틱마다 잠그는 방식(ShedLock)이 아니라 한 서버가 계속
 * 맡는 리더 방식인 까닭은, 분석 파이프라인 단계(`ScoreStep`·`EmbedStep`·`AnalysisSweepWatch`)가 틱 사이에 서버 메모리 상태(무진행
 * 안전망 시계·GPU 켜기 판단·되돌리기 경계)를 쥐기 때문이다 — 틱이 서버 사이를 오가면 이 상태가 갈라진다.
 *
 * 예외: 서버마다 자기 메모리를 비우는 작업(활동 시각 버퍼 플러시)은 리더를 보지 않고 모든 서버에서 돈다.
 * 새 `@Scheduled` 작업은 첫 줄에서 이것을 확인하거나, 서버마다 돌아야 하는 까닭을 `ScheduledLeadershipGuardTest`의 예외 목록에 남긴다.
 */
interface SchedulerLeadership {
    fun isLeader(): Boolean
}

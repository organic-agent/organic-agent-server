package com.soma.wes.global.cluster

/**
 * 이 서버가 방금 주기 작업 리더가 됐다. 리더가 아닌 동안 낡은 서버 메모리 상태(멈춤 감시의 마지막 완료 시각 등)를 새로 맞출 자리다.
 * 서버가 한 대(리더 임대 꺼짐)면 발행되지 않는다 — 처음부터 리더다.
 */
class SchedulerLeadershipAcquiredEvent

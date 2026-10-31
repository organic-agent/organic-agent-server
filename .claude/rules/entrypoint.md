---
description: 입구(scheduler·listener) 컨벤션 — 시간·이벤트·기동이 깨우는 클래스의 자리와 형태
paths:
  - "wes-*/src/main/kotlin/**/*.kt"
---

# 입구 컨벤션 (scheduler · listener)

컨트롤러가 HTTP의 입구라면, 스케줄러는 시간의 입구, 리스너는 이벤트(앱 기동 포함)의 입구다. 무엇이 깨우느냐만 다르고
하는 일은 같다 — **service를 부르고, 판단은 service가 한다.** 입구는 support가 아니다: support는 service가 기대는
협력자이고, 입구는 service를 부르는 쪽이다(`support.md`, 2026-10-31 결정).

## 자리

| 깨우는 것 | 패키지 | 예 |
|---|---|---|
| 시간(`@Scheduled`) | `{domain}/scheduler/` | `TrashPurgeScheduler`, `AnalysisPipelineScheduler` |
| 이벤트(`@EventListener`·`@TransactionalEventListener`·SQS) | `{domain}/listener/` | `AnalysisJobEventListener` <!-- EP-02 --> |
| 앱 기동(`ApplicationRunner`·`ApplicationReadyEvent`) | `{domain}/listener/` | `AdminRecoveryRunner` |

- 패키지는 그 종류의 입구가 생길 때만 만든다.
- 이름은 깨우는 것을 드러낸다: `~Scheduler`, `~Listener`, `~Runner`. <!-- EP-03 -->
- service 클래스 안에 `@Scheduled`·`@EventListener` 메서드를 두지 않는다 — 입구가 service 안에 숨으면 "누가 이 유스케이스를
  부르나"가 패키지로 안 보인다. <!-- EP-01 -->
- 기존 위치(`support/`의 스케줄러·리스너 14개, admin service 안의 `@Scheduled` 3개 — `rule-audit/reference/allowlist-entrypoint.txt`)는
  옮기기 전까지 규칙 검토의 허용 목록이다.
- 무엇이 깨우는가로 가른다: SQS를 읽는 `SmartLifecycle`(`AiFailureListener`)은 이벤트 입구다. 리더 임대처럼 서버 생명주기에
  붙은 인프라(`LeasedSchedulerLeadership`)는 입구가 아니다. 이름이 `~Runner`라도 다른 서비스가 부르는 클래스(`AiSelectionJobRunner`)는 service다.
- 새로 만드는 입구는 위 자리에 둔다.

## 형태

- 얇게: 리더 확인 → service 메서드 한 번 호출 → (필요하면) 결과 로그. repository를 직접 부르거나 분기 판단을 하지 않는다. <!-- EP-04 -->
- 트랜잭션은 입구가 아니라 부르는 service 메서드에 둔다. <!-- EP-07 -->

## 주기 작업(`@Scheduled`)

- 첫 줄에서 `if (!leadership.isLeader()) return`으로 리더 한 대만 돌게 한다(`global/cluster/SchedulerLeadership`). 서버마다 메모리에
  쌓인 것을 비우는 일(활동 시각 버퍼 플러시)만 예외로 모든 서버가 돈다. `ScheduledLeadershipGuardTest`가 새 `@Scheduled`의 가드 누락을 잡는다. <!-- EP-05 -->
- **관리자 앱(wes-admin-api)은 예외다** — 서버가 1대라 경쟁할 상대가 없고, `com.soma.wes.admin`만 스캔해 `SchedulerLeadership` 빈도 없다.
  재검토 조건: 관리자 서버를 2대 이상으로 늘리는 날 관리자 앱에도 리더 가드를 넣고 가드 테스트를 관리자 쪽에도 둔다.
- **중복 방지를 리더 하나에 기대지 않는다.** 리더의 갱신이 멈춘 사이 임대가 끝나면 옛 리더가 하던 틱과 새 리더의 틱이 겹칠 수 있다(#313 ADR-0002 §7). 두 서버가 같은 틱을 돌아도
  결과가 한 번이도록 조건부 UPDATE(영향 행 0이면 손 뗌)·`FOR UPDATE SKIP LOCKED`·행 잠금 + 발행 표식, 또는 두 번 해도 같은 일(삭제·UPSERT)로 짠다. <!-- EP-06 -->

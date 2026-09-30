---
paths:
  - "src/main/kotlin/**/*.kt"
---

# Infrastructure 컨벤션

외부 시스템(S3, Lambda, OAuth 제공자, …)은 **그 도메인의 `infrastructure` 패키지에 있는
어댑터를 통해서만** 닿는다. 최상위 `infrastructure` 패키지는 만들지 않는다 — 어댑터는
그것을 쓰는 도메인의 것이다.

## Port와 Adapter

- 도메인은 자기 `service/port` 패키지에 **port 인터페이스**를 선언한다. 이름과 시그니처는 도메인
  어휘로 쓰고 벤더 타입을 노출하지 않는다 (`PhotoStorage.presignUpload`, `StageInvoker.invoke`).
  **포트는 "이 도메인이 선언하고 바깥이 구현하는 인터페이스"다** — 구현하는 쪽이 `infrastructure`
  어댑터든 다른 도메인이든 모두 여기다. 다른 도메인이 구현하는 도메인 간 훅도 포트다
  (`folder/service/port/FolderReactionCleaner` ← `collab/support/CollabFolderReactionCleaner`).
  `service` 루트에 인터페이스를 두지 않는다 — 그러면 "바깥에 기대는 지점"이 패키지로 보이지 않는다.
- 포트가 주고받는 값 타입은 `dto/` 루트의 `~Dto`다 (`StageCallDto`, `ScoreWorkerDto`). 포트 파일에
  데이터 클래스를 같이 두지 않는다.
- `{domain}/infrastructure`의 **어댑터**가 그것을 구현하며, 기술 이름을 앞에 붙인다
  (`S3PhotoStorage`, `LambdaStageInvoker`). 서비스는 port만 주입받는다.
  예외로 `@Profile("local")` 전용 대역 어댑터는 기술 이름 대신 `Local`을 붙인다 — 운영 어댑터와 짝이 되는
  것은 "어디서 도느냐"라서다 (`Ec2ScoreWorkerPool` ↔ `LocalScoreWorkerPool`, `LambdaStageInvoker` ↔ `LocalStageInvoker`).
- SDK 클라이언트 빈과 프로퍼티는 그 도메인의 `config`에 둔다 (`embedding/config/AwsLambdaConfig`).
  두 번째 도메인이 같은 클라이언트를 쓰게 되는 날에만 `global/config`로 올린다.

## 경계 규칙

- **SDK 타입을 import할 수 있는 곳은 `infrastructure`와 `config`뿐이다.** 다음 명령이 항상
  비어 있어야 한다:
  ```
  grep -rln "software.amazon.awssdk" src/main/kotlin | grep -vE "/(infrastructure|config)/"
  ```
- 벤더 예외는 어댑터가 잡아 도메인 예외로 바꾼다
  (`SdkException` → `PhotoException(STORAGE_DELETE_FAILED)`). 서비스는 벤더 예외를 모른다.
- 로깅도 경계를 지킨다: 어댑터는 벤더 수준(버킷, 함수명, 에러 코드)을, 서비스는 도메인 사건을
  로깅한다. 어댑터의 로거는 `LoggerFactory.getLogger(javaClass)`.
- 의존은 안쪽으로만: 어댑터는 자기 도메인의 `config` 프로퍼티·`exception`·전달용 DTO를 쓸 수 있다.
  어댑터가 `service`·`repository`를 부르거나, 다른 도메인을 import하지 않는다.

## 어댑터 내부

- 벤더의 하드 리밋은 companion 상수로 새기고 처리한다
  (`S3PhotoStorage.MAX_DELETE_OBJECTS = 1000`, `chunked` 처리).
- 부분 실패를 돌려주는 API는 응답의 에러 목록까지 확인한다 (`DeleteObjects`의 `hasErrors()`).
- 어댑터는 재시도·폴백을 스스로 결정하지 않는다. 실패를 도메인 예외로 알리고, 재시도 정책은
  호출한 서비스의 것이다 (`TrashEraser`의 S3-first, 다음 틱 재시도 전례).

이 분리의 목적은 교체 가능성이 아니다 — 아무도 S3를 갈아끼우지 않는다. 벤더 타입이 서비스
생성자에 들어오는 순간 그 예외·재시도·테스트 더블이 전부 도메인의 문제가 되는 것을 막는 것이다.

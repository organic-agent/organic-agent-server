---
description: 패키지 배치 규칙 — 도메인 단위 패키지와 도메인 내부 구조
paths:
  - "src/main/kotlin/**/*.kt"
  - "src/test/kotlin/**/*.kt"
---

# 프로젝트 구조

기본 패키지는 `com.soma.wes`. 웹 스택은 `spring-boot-starter-webmvc`(서블릿 MVC, WebFlux 아님).

## 도메인 단위 패키지

`auth`, `user`, `studio`, `gallery`, `photo`, `embedding`, `cluster`, `folder`,
`selection`, `collab`, `trash`, `retouch`, `recommendation`, `admin` + 횡단 관심사 `global`/`security`.

- `embedding`~`recommendation`은 전부 사진에 *관한* 도메인이지만 `photo`의 하위 패키지가
  아니다 — 각자 service·controller·config를 소유하고, `folder`/`selection`/`collab`은 자기
  엔티티도 가진다. 합치면 `photo`가 모든 것이 떨어지는 패키지가 된다.
- 새 도메인 이름은 소문자 한 단어. 두 단어가 되면 경계를 다시 생각하라.

## 도메인 내부 구조

```
{domain}/
├── domain/          # 엔티티·값객체 (→ rules/domain.md)
├── repository/      # Spring Data 인터페이스 (→ rules/repository.md)
│   └── projection/  # 조회 전용 projection
├── service/         # 컨트롤러가 부르는 유스케이스 + port 인터페이스 (→ rules/service.md)
├── support/         # 유스케이스가 기대는 협력자 (→ rules/support.md)
├── controller/      # REST 컨트롤러 (→ rules/controller.md)
│   └── docs/        # OpenAPI 애노테이션 인터페이스 ({Name}ControllerDocs)
├── dto/             # 내부 전달용 ~Dto (→ rules/dto.md)
│   ├── request/
│   └── response/
├── exception/       # {Domain}Exception + {Domain}ErrorCode
├── config/          # 프로퍼티·SDK 클라이언트 빈
└── infrastructure/  # 외부 시스템 어댑터 (→ rules/infrastructure.md)
```

- **최상위 `infrastructure` 패키지는 없다.** 외부 시스템 어댑터는 그것을 쓰는 도메인 안에 산다.
  모든 벤더 클래스를 한 바구니에 모으면 각 도메인이 둘로 쪼개진다.
- 도메인 공용 클라이언트 빈은 두 번째 도메인이 필요로 하는 날에만 `global/config`로 올린다.
- 테스트는 프로덕션 패키지를 그대로 미러링한다.

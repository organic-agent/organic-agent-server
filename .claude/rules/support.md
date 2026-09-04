---
paths:
  - "src/main/kotlin/**/*.kt"
---

# Support 컨벤션

`{domain}/support`는 유스케이스가 기대는 협력자의 자리다. 구분 기준은 호출자다:
**컨트롤러가 부르면 `service`, 서비스가 기대면 `support`.**

## 무엇이 여기 사는가

- 인가 정책: `gallery/support/GalleryAccessPolicy`, `collab/support/CollabSessionAccess`
- 응답 조립자: `photo/support/PhotoViewAssembler`, `folder/support/FolderViewAssembler`
- 공용 로더/검증자: `folder/support/FolderPhotoLoader`
- 토큰 생성기·설정 리더: `gallery/support/GalleryInviteTokenGenerator`, `auth/support/OAuthRegistrations`

트랜잭션이 필요하면 support 클래스도 `@Service`를 붙인다 — **애노테이션은 Spring 사정이고,
패키지가 클래스의 역할을 말한다.** 테스트는 패키지를 그대로 미러링한다.

## 정책 클래스 (AccessPolicy)

- **메서드 이름은 능력이 아니라 역할을 따른다**: `requirePhotographer`, `requireCouple`,
  `requireViewer`. "canUpload" 같은 능력 이름을 만들지 마라 — 새 엔드포인트가 어느 문을 쓸지는
  "보는 것인가, 고르는 것인가"라는 질문으로 정한다.
- 각 메서드의 KDoc에는 그 문을 쓰는 유스케이스를 나열한다 (어느 문이 맞는지 찾는 목차가 된다).
- 엔티티를 반환해도 된다 — 호출자(서비스)와 같은 트랜잭션 안이다.
- 역할 전제가 다른 호출자는 다른 정책 클래스로 분리한다: 계정 없는 게스트는
  `CollabSessionAccess`(`requireReadable`/`requireWritable`/`requireGuest`)가 담당하고
  `GalleryAccessPolicy`를 건드리지 않는다.

## 조립자 (ViewAssembler)

- 화면 규칙의 단일 소유자다: "PENDING이면 URL은 null", "게스트에게 별점을 숨긴다" 같은 규칙이
  모든 화면에 같게 적용되는 곳 (`PhotoViewAssembler`).
- **단건 API와 배치 API를 함께 제공하고, 목록은 반드시 배치로 간다.** `toResponse`는 호출당
  쿼리 1회 — 루프에서 부르면 N+1이다. 폴더 여러 개를 요약할 때 항목·사진·별점을 각각 한 번씩만
  조회하는 `FolderViewAssembler.summariesByFolderId`가 기준 형태다.

## 로더/검증자

- 여러 서비스가 공유하는 검증 묶음은 support로 모은다. `FolderPhotoLoader.loadPhotos`는
  "이 갤러리의 사진인가, 상한을 넘지 않는가, 비어 있지 않은가"를 한 곳에서 지키고 정렬까지
  책임진다 — 서비스마다 반쯤 복제된 검증이 생기면 이리로 옮길 신호다.
- 검증 메서드 이름은 `validate...`/`require...`로 시작하고, 실패는 도메인 예외로 던진다.

## 도메인 사이의 문

- 다른 도메인이 이 도메인을 쓸 때의 입구도 support다 — `folder`·`selection`·`collab` 서비스가
  `gallery/support/GalleryAccessPolicy`를 주입받는다. 다른 도메인의 repository를 직접 주입하는
  것보다 정책/로더를 거치는 쪽을 우선하라.

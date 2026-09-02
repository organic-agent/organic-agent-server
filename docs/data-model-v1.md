# WES 데이터 모델 V1

이 문서는 신규 환경에 적용하는 단일 Flyway 베이스라인 `V1__baseline.sql`의 핵심 계약을 정리한다.

## 작업공간과 권한

- 모든 사용자는 가입할 때 `PERSONAL` 작업공간 하나와 `OWNER` 멤버십 하나를 가진다.
- 스튜디오는 `STUDIO` 작업공간을 확장하며 `studios.workspace_id`를 공유 PK로 사용한다.
- 한 사용자는 여러 스튜디오에 속할 수 있고, 한 스튜디오는 여러 `OWNER`와 `MEMBER`를 가질 수 있다.
- 갤러리는 사용자나 스튜디오가 아니라 `workspace_id`에 속한다. 생성자는 `created_by_user_id`로 별도 기록한다.
- `STUDIO`의 OWNER와 MEMBER는 갤러리를 관리·조회하지만, 선택과 별점 수정은 초대 멤버가 담당한다.
- `PERSONAL` 갤러리는 작업공간 OWNER와 초대 멤버가 선택과 별점을 수정한다.
- 갤러리 초대 인원은 최대 2명이며, 같은 갤러리의 작업공간 구성원은 초대를 수락할 수 없다.

관리자 사용자 연쇄 삭제는 개인 작업공간과 그 사용자가 유일한 OWNER인 스튜디오만 포함한다. 다른 활성 OWNER가 있는 공동 소유 스튜디오는 보존하고 해당 사용자 멤버십만 제거한다.

## 갤러리 상태

- 고객 노출 상태: `DRAFT`, `OPEN`, `CLOSED`
- 제작 워크플로 상태: `DRAFT`, `IN_PROGRESS`, `COMPLETED`, `ARCHIVED`

두 상태는 독립적으로 저장하고 변경한다.

## 카테고리와 사진 배정

- `concept_folders` 1:N `detail_folders`
- 사진은 `photo_category_assignments`를 통해 최대 하나의 상세폴더에만 배정된다.
- 상세폴더를 삭제하면 사진은 미분류 상태가 된다.
- 컨셉폴더를 삭제하면 상세폴더, 협업 세션, 하객과 그 컨셉에 속한 사진 반응이 함께 제거된다.
- 최초 자동 분류는 갤러리의 전체 대상 사진을 처리한다. 이후 증분 실행은 처리 이력이 없는 사진만 대상으로 삼는다.
- 사용자가 수동으로 미분류로 옮긴 사진은 자동 증분 실행이 다시 배정하지 않는다.

## 동적 협업

- 컨셉폴더 하나에는 협업 세션과 링크가 최대 하나만 존재한다.
- `collab_photos` 같은 중간 스냅샷 테이블은 없다. 조회 시 컨셉 아래 상세폴더의 현재 사진 배정을 읽는다.
- 댓글과 좋아요는 `collab_session_id`, 실제 `photo_id`, `collab_guest_id`를 직접 참조한다.
- 사진이 같은 컨셉 안에서 상세폴더만 이동하면 댓글과 좋아요를 유지한다.
- 사진이 다른 컨셉이나 미분류 상태로 이동하면 이전 컨셉 세션의 댓글과 좋아요를 제거한다.
- 활성 좋아요는 세션·사진·하객 조합당 하나만 허용한다.

## BackOffice 리소스 계약

- 공통 검색·상세·컨텍스트는 `WORKSPACE`, `CONCEPT_FOLDER`, `DETAIL_FOLDER`,
  `PHOTO_CATEGORY_ASSIGNMENT`, `CATEGORIZATION_JOB`, `PHOTO_RATING`을 포함한다.
- 사진 배정과 평점의 관리자 리소스 ID는 모두 `photoId`다. 분류 작업의 처리 사진은
  복합키 행을 별도 리소스로 만들지 않고 `CATEGORIZATION_JOB.sections.photos`에 노출한다.
- `USER` 일반 CRUD에는 전역 `role` 또는 `userType` 필드가 없다. 작업공간 권한은
  `workspaceId`, `workspaceType`, `accessRole`로만 표현한다.
- `WORKSPACE`와 `CATEGORIZATION_JOB`은 직접 생성·삭제할 수 없다. 분류 작업은 갤러리의
  `RUN_CATEGORIZATION` 워크플로로 실행한다.
- `PHOTO_CATEGORY_ASSIGNMENT`와 `PHOTO_RATING` 삭제는 즉시 반영한다.
- `CONCEPT_FOLDER`와 `DETAIL_FOLDER` 삭제는 7일 복원 가능한 관리자 배치다. 사용자 또는
  스튜디오 연쇄 삭제 시 소유 `WORKSPACE`도 같은 배치에서 숨기고 복원한다.
- 리소스 컨텍스트의 `facts.trashBatchId`는 활성 관리자 휴지통 배치를 가리킨다.
  `facts.canRestoreDirectly`는 해당 리소스가 아직 복원 가능한 배치 루트일 때만 `true`다.
- 대리보기는 `viewer.accessRole`, `view.workspaces`를 사용한다. 갤러리는
  `workspaceId`, `workspaceType`, `createdByUserId`, `publicStatus`, `workflowStatus`를 분리해 반환한다.

## Flyway 적용 계약

- 마이그레이션 파일은 `src/main/resources/db/migration/V1__baseline.sql` 하나다.
- `baseline-on-migrate`는 `false`다. 비어 있지 않은 기존 스키마를 자동으로 베이스라인 처리하지 않는다.
- V1부터 V44까지 적용된 기존 DB와 이 단일 V1은 Flyway 이력과 체크섬이 호환되지 않는다.
- 로컬·테스트 DB는 스키마 또는 DB를 비운 뒤 애플리케이션을 시작해 V1을 새로 적용한다.
- 운영 DB 전환은 별도의 백업, 데이터 이관, 리허설, 롤백 계획 없이는 진행하지 않는다.

신규 적용 후에는 다음을 확인한다.

1. `flyway_schema_history`에 성공한 버전 `1`이 한 건만 존재한다.
2. `workspaces`, `workspace_members`, `concept_folders`, `detail_folders`, `photo_category_assignments`가 존재한다.
3. `studio_members`, `collab_photos`, `users.user_type`이 존재하지 않는다.
4. `studios.workspace_id`가 PK이고 `galleries.workspace_id`가 작업공간을 참조한다.
5. 임베더 역할은 사진 임베딩 처리에 필요한 최소 권한만 가진다.

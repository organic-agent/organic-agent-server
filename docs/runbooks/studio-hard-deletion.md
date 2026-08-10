# 스튜디오 hard delete 운영 절차

이 절차는 사용자가 문의로 스튜디오 삭제를 요청했고, 운영자가 요청자와 대상을 확인한 경우에만 사용한다.
사진은 다시 촬영하기 어렵고 삭제 후 복구할 수 없으므로 일반 사용자에게 삭제 API를 제공하지 않는다.

## 배포 전 스키마 확인

V9는 외래키를 `NOT VALID`로 설치하고 V10이 기존 행을 검증한다. 고아 행이 있으면 V10이
중단되므로 배포 전에 다음 질의가 모두 `0`인지 확인한다.

V9는 기존 사진과 rolling 배포 중 구버전 서버가 만든 사진의
`upload_url_expires_at`을 `CURRENT_TIMESTAMP + 30 minutes`로 채운다. 신버전은 SDK의 실제
만료 시각으로 덮어쓴다. 처음 30분 동안 기존 사진이 있는 스튜디오 삭제가
`STUDIO_409_8`로 보수적으로 막히는 것은 정상이다. 현재 TTL 설정 `30m`을 늘리는 배포에서는
이 cutover default도 같은 값으로 먼저 늘린다.

구버전은 사진 INSERT 뒤 URL을 서명하므로 DB default만으로는 그 순서 차이를
완전히 덮지 못한다. 모든 구버전 인스턴스와 in-flight 요청을 drain한 시각부터
`app.storage.upload-url-ttl` 30분과 승인된 clock/network safety margin이 모두 경과하기
전에는 hard delete flag를 활성화하지 않는다.

```sql
SELECT count(*) FROM galleries g LEFT JOIN studios s ON s.id = g.studio_id WHERE s.id IS NULL;
SELECT count(*) FROM gallery_members gm LEFT JOIN galleries g ON g.id = gm.gallery_id WHERE g.id IS NULL;
SELECT count(*) FROM gallery_invites gi LEFT JOIN galleries g ON g.id = gi.gallery_id WHERE g.id IS NULL;
SELECT count(*) FROM photos p LEFT JOIN galleries g ON g.id = p.gallery_id WHERE g.id IS NULL;
SELECT count(*) FROM photo_folders pf LEFT JOIN galleries g ON g.id = pf.gallery_id WHERE g.id IS NULL;
SELECT count(*) FROM photo_folder_items pfi LEFT JOIN photo_folders pf ON pf.id = pfi.folder_id WHERE pf.id IS NULL;
SELECT count(*) FROM photo_folder_items pfi LEFT JOIN photos p ON p.id = pfi.photo_id WHERE p.id IS NULL;
SELECT count(*) FROM photo_selections ps LEFT JOIN galleries g ON g.id = ps.gallery_id WHERE g.id IS NULL;
SELECT count(*) FROM photo_selection_items psi LEFT JOIN photo_selections ps ON ps.id = psi.selection_id WHERE ps.id IS NULL;
SELECT count(*) FROM photo_selection_items psi LEFT JOIN photos p ON p.id = psi.photo_id WHERE p.id IS NULL;
SELECT count(*) FROM photo_ratings pr LEFT JOIN photos p ON p.id = pr.photo_id WHERE p.id IS NULL;
```

0이 아닌 결과를 마이그레이션에서 자동 삭제하지 않는다. 운영 데이터의 소유 관계를 확인한 뒤 별도 정리한다.

## Embedding writer 배포 확인

Embedding Lambda는 앱과 별도 이미지로 배포되고 RDS를 직접 갱신한다. hard delete를 처음
활성화하기 전에 다음 두 조건을 모두 확인한다.

1. embedder DB 사용자에 `GRANT SELECT ON galleries, studio_deletion_claims TO embedder;`가 적용됐다.
2. 배포된 Lambda 이미지가 `studio_write_admission` session advisory shared lock과 삭제 claim
   검사를 포함한다.

둘 중 하나라도 확인되지 않으면 hard delete를 실행하지 않는다. Lambda가 삭제 스냅샷 전에
사진을 읽고 S3 삭제 뒤 preview를 쓰면 DB와 객체 저장소의 원자적 복구가 불가능하다.

## 기능 활성화 체크리스트

코드와 스키마를 merge·배포하는 것만으로 hard delete가 켜지지 않는다.
`app.studio-hard-deletion.enabled` 기본값은 `false`이고, 유효한 운영자 요청도
`STUDIO_503_1`로 중단된다. 이미 완료된 같은 요청의 audit 재생만 200을 유지한다.
`RETRYABLE` claim의 재개도 새 S3 삭제이므로 flag가 꺼진 동안은 일시 중지한다.
아래를 모두 증빙한 환경에서만 배포 설정을 `true`로 덮어쓴다.

1. V9·V10이 적용됐고 배포 전 고아 행 질의가 모두 `0`이다.
2. 모든 앱 인스턴스가 studio writer admission과 default-off gate를 포함한 버전이고,
   구버전·in-flight 요청 drain 시각으로부터 URL TTL 30분+승인된 safety margin이
   경과했다.
3. 위 Embedding writer의 DB SELECT 권한과 새 Lambda 이미지가 함께 반영됐다.
4. S3 ObjectCreated late PUT cleanup이 배포됐거나, 보완 전 업로드 중지·마지막 URL
   만료·in-flight PUT 종료를 확인하는 승인된 운영 통제와 담당자·로그 경로가
   정해졌다.
5. 활성화 변경·시각·승인자를 운영 기록에 남겼고, 문제 발생 시 즉시 `false`로
   되돌릴 배포 경로를 확인했다.

이 체크리스트는 PR merge 조건이 아니라 **운영 환경의 flag 활성화 조건**이다.

## 실행 전 확인

1. 문의 채널에서 요청자의 로그인 계정과 스튜디오 소유자가 같은 사람인지 확인한다.
2. 삭제할 스튜디오의 숫자 ID와 현재 `galleryUrl`을 각각 확인한다.
3. 스튜디오 아래의 모든 갤러리, 초대, 멤버, 사진 메타데이터, S3 원본과 미리보기가 영구 삭제됨을 알린다.
4. 스튜디오가 보유한 갤러리 수와 사진 수를 요청자에게 안내하고 최종 삭제 의사를 다시 확인한다.
5. 문의 티켓 번호와 최종 확인 시각을 실행 사유에 남긴다.
6. 요청 한 건마다 UUID 형식의 `Idempotency-Key`를 하나 발급한다. 재시도할 때는 반드시 같은 값을 쓴다.

하나라도 확인할 수 없거나 문의 내용과 대상이 다르면 실행하지 않는다.

## 권한과 실행 경로

- `ROLE_ADMIN`이 포함된 운영자 access token만 사용할 수 있다.
- 사용자용 `DELETE /api/v1/studios/me`는 존재하지 않는다.
- 내부 실행 경로는 공개 OpenAPI 문서에서 숨긴다.

```http
POST /api/v1/admin/studios/{studioId}/hard-delete
Authorization: Bearer {operator-access-token}
Idempotency-Key: {request-uuid}
Content-Type: application/json

{
  "confirmedGalleryUrl": "현재 스튜디오의 galleryUrl",
  "reason": "문의 티켓 WES-CS-1234, 소유자와 2026-08-06 21:00 KST 최종 확인"
}
```

## 서버가 실행하는 순서

1. 같은 `Idempotency-Key`의 완료 기록이 있으면 저장된 결과를 그대로 반환한다.
2. 숫자 ID로 찾은 스튜디오의 현재 `galleryUrl`이 운영자가 입력한 확인값과 같은지 검사한다.
3. studio 행을 잠그고 같은 studio write fence의 exclusive advisory lock을 얻는다. 실행 중인
   Embedding job의 shared lock이 있으면 S3를 만지기 전에 `STUDIO_409_9`로 중단한다.
4. `RUNNING` 상태의 스튜디오당 하나인 삭제 claim을 만든다. 이후 일반 갤러리·사진 writer는 같은 shared fence
   뒤 claim을 확인해 저장 전에 `STUDIO_409_7`로 거절된다. Mock seed처럼 자체 멱등성에도
   부모 mutex가 필요한 writer만 studio 행 잠금을 추가로 쓴다.
5. 갤러리와 사진을 조회해 삭제 스냅샷을 만든다. 사진 행에 기록된 실제 PUT URL 만료 시각이
   하나라도 지나지 않았으면 claim을 포함한 준비 트랜잭션을 롤백하고 `STUDIO_409_8`로 중단한다.
   업로드 완료 통보는 기존 사진의 `status`만 바꾸고 키·행·S3 객체를 만들지 않으므로
   삭제 claim 뒤에 들어와도 이 snapshot의 저장소 무결성을 바꾸지 않는다.
6. S3 원본과 미리보기를 최대 1,000개씩 삭제한다. DB의 `preview_key`와 원본 키에서 계산한 예상 미리보기 키를 모두 지우며, 존재하지 않는 객체의 재삭제는 성공으로 본다.
   이 시점 뒤 실패하면 claim을 지우지 않고 `RETRYABLE`로 바꿔 writer를 계속 막는다.
   같은 요청·본문만 새 token으로 `RUNNING`을 재점유해 snapshot과 S3 삭제를 멱등하게 재개한다.
   claim의 `plan_version`이 현재 artifact가 지원하는 값과 다르면 재개하지 않는다.
7. 스튜디오와 갤러리·사진을 다시 잠그고 스냅샷이 달라지지 않았는지 검사한다.
8. 스튜디오를 삭제한다. DB의 `ON DELETE CASCADE`가 갤러리, 초대, 멤버, 사진 메타데이터, 폴더, 선택 앨범, 별점을 같은 트랜잭션에서 삭제한다.
9. 요청 ID, 대상, 실행 운영자, 사유, 삭제 수량을 `studio_deletion_audits`에 남긴다.

사용자 계정과 `userType=PHOTOGRAPHER`는 삭제하지 않는다. 사용자는 같은 계정으로 로그인할 수 있고 새 스튜디오를 다시 만들 수 있다.

## 결과 확인

성공 응답의 `auditId`, `requestId`, `studioId`, `galleryCount`, `photoCount`, `objectCount`, `executedAt`을 문의 티켓에 기록한다.

- 같은 요청 ID로 다시 실행해 동일한 `auditId`가 반환되는지 확인한다.
- `studios`, `galleries`, `gallery_invites`, `gallery_members`, `photos`, `photo_folders`, `photo_folder_items`, `photo_selections`, `photo_selection_items`, `photo_ratings`에 대상 행이 남지 않았는지 확인한다.
- `studio_deletion_audits`의 실행자와 사유가 문의 티켓과 일치하는지 확인한다.

## 실패 처리

- `STUDIO_409_3`: 확인한 `galleryUrl`과 현재 대상이 다르다. 대상 확인부터 다시 한다.
- `STUDIO_409_4`: 같은 요청 ID가 다른 대상·운영자·사유에 사용됐다. 재사용하지 말고 요청 관계를 조사한다.
- `STUDIO_409_5`: S3 삭제 준비 중 갤러리나 사진이 변경됐다. claim은 `RETRYABLE`로 남아 writer를 막는다. **같은 요청 ID와 같은 본문**으로 다시 실행한다.
- `STUDIO_409_6`: 같은 요청 ID가 이미 실행 중이다. 기존 실행 결과를 확인한 뒤 같은 요청으로 다시 시도한다.
- `STUDIO_409_7`: 삭제 claim 뒤 갤러리·사진 writer가 들어왔다. 진행 중인 삭제 결과를 먼저 확인한다.
- `STUDIO_409_8`: 아직 유효한 PUT URL이 있다. 새 업로드를 멈춘 상태에서 URL이 만료된 뒤 같은 요청과 본문으로 다시 실행한다.
- `STUDIO_409_9`: studio row lock에서 완료를 snapshot에 흡수하지 못한 앱 writer나 Embedding Lambda가 실행 중이다. writer 완료를 확인한 뒤 같은 요청과 본문으로 다시 실행한다.
- `STUDIO_409_10`: 현재 artifact가 claim의 retry `plan_version`을 지원하지 않는다. claim을 지우지 말고 그 버전을 지원하는 동일 artifact로 재개하거나 영구 호환 recovery를 먼저 배포한다.
- `PHOTO_502_2`: S3 삭제가 전부 끝나지 않았다. DB 삭제는 실행되지 않고 claim은 `RETRYABLE`로 남아 writer를 막는다. 원인을 확인한 뒤 같은 요청으로 재시도한다.
- 그 밖의 5xx: DB에 대상 스튜디오가 남아 있으면 claim을 임의로 지우지 말고 같은 요청으로 재시도한다. 스튜디오가 없으면 감사 기록을 확인하고 임의로 새 요청을 만들지 않는다.

부분 실패를 수동 SQL로 이어서 처리하지 않는다. S3 키와 감사 기록을 잃으면 삭제 범위를 검증할 수 없으므로 항상 동일 요청의 서버 절차로 복구한다.

프로세스 강제 종료로 `studio_deletion_claims`에 실행 점유만 남으면 자동 만료시키지 않는다. 느린 정상
실행을 만료로 오판하면 S3 삭제가 다시 겹칠 수 있기 때문이다. 실행 프로세스가 없고 완료 감사 기록도
없음을 `claimed_at`·`state_changed_at`과 애플리케이션 로그로 확인한 뒤에도 claim을
제거하지 않는다. 조사한 request·token·`RUNNING`을 모두 CAS 조건으로 삼아 다음 전이만
수행한다.

```sql
UPDATE studio_deletion_claims
SET state = 'RETRYABLE', state_changed_at = statement_timestamp()
WHERE request_id = :observed_request_id
  AND claim_token = :observed_claim_token
  AND state = 'RUNNING';
```

변경 행 수가 정확히 `1`이 아니면 재시도하지 않고 invariant alert를 조사한다.
감사 기록이 있으면 claim을 고치지 않고 완료 응답을 재생한다. 전이 성공 후에는 같은
요청·본문으로 즉시 재시도한다. S3 삭제 여부를 모르는 간격에 writer gate가
열리지 않게 하는 절차다.

`PhotoDeletionTarget.expectedPreviewKey`와 snapshot 필드는 retry plan 규칙이다. 그 규칙을 바꾸는
배포는 `plan_version`을 올리고 기존 버전 recovery를 영구 호환으로 유지하거나,
남은 claim을 동일 artifact로 모두 완료한 뒤에만 활성화한다.

## 남는 S3 경계

S3는 presigned URL 만료 여부를 HTTP 요청이 **시작될 때** 검사한다. 서버는 SDK가 돌려준 실제
만료 시각 전에는 삭제를 시작하지 않지만, 만료 직전에 시작한 단일 PUT이 진행 중인지는 알 수
없다. 그 PUT이 S3 삭제보다 늦게 완료되는 극단 경계까지 제거하려면 S3 ObjectCreated 이벤트로
DB tombstone을 확인해 orphan을 다시 지우거나, studio prefix의 쓰기를 저장소 계층에서 차단하는
인프라 보완이 필요하다. 이 보완 전에는 업로드를 중지하고 마지막 URL 만료 뒤에도 진행 중인
업로드가 없음을 운영 로그에서 확인한 뒤 실행한다.

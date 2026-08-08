# 스튜디오 hard delete 운영 절차

이 절차는 사용자가 문의로 스튜디오 삭제를 요청했고, 운영자가 요청자와 대상을 확인한 경우에만 사용한다.
사진은 다시 촬영하기 어렵고 삭제 후 복구할 수 없으므로 일반 사용자에게 삭제 API를 제공하지 않는다.

## 배포 전 스키마 확인

V9 마이그레이션은 기존 고아 행이 있으면 중단된다. 배포 전에 다음 질의가 모두 `0`인지 확인한다.

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
3. 갤러리와 사진을 조회해 삭제 스냅샷을 만든다.
4. S3 원본과 미리보기를 최대 1,000개씩 삭제한다. DB의 `preview_key`와 원본 키에서 계산한 예상 미리보기 키를 모두 지우며, 존재하지 않는 객체의 재삭제는 성공으로 본다.
5. 스튜디오와 갤러리·사진을 다시 잠그고 스냅샷이 달라지지 않았는지 검사한다.
6. 스튜디오를 삭제한다. DB의 `ON DELETE CASCADE`가 갤러리, 초대, 멤버, 사진 메타데이터, 폴더, 선택 앨범, 별점을 같은 트랜잭션에서 삭제한다.
7. 요청 ID, 대상, 실행 운영자, 사유, 삭제 수량을 `studio_deletion_audits`에 남긴다.

사용자 계정과 `userType=PHOTOGRAPHER`는 삭제하지 않는다. 사용자는 같은 계정으로 로그인할 수 있고 새 스튜디오를 다시 만들 수 있다.

## 결과 확인

성공 응답의 `auditId`, `requestId`, `studioId`, `galleryCount`, `photoCount`, `objectCount`, `executedAt`을 문의 티켓에 기록한다.

- 같은 요청 ID로 다시 실행해 동일한 `auditId`가 반환되는지 확인한다.
- `studios`, `galleries`, `gallery_invites`, `gallery_members`, `photos`, `photo_folders`, `photo_folder_items`, `photo_selections`, `photo_selection_items`, `photo_ratings`에 대상 행이 남지 않았는지 확인한다.
- `studio_deletion_audits`의 실행자와 사유가 문의 티켓과 일치하는지 확인한다.

## 실패 처리

- `STUDIO_409_3`: 확인한 `galleryUrl`과 현재 대상이 다르다. 대상 확인부터 다시 한다.
- `STUDIO_409_4`: 같은 요청 ID가 다른 대상·운영자·사유에 사용됐다. 재사용하지 말고 요청 관계를 조사한다.
- `STUDIO_409_5`: S3 삭제 준비 중 갤러리나 사진이 변경됐다. **같은 요청 ID와 같은 본문**으로 다시 실행한다.
- `PHOTO_502_2`: S3 삭제가 전부 끝나지 않았다. DB 삭제는 실행되지 않는다. 원인을 확인한 뒤 같은 요청으로 재시도한다.
- 그 밖의 5xx: DB에 대상 스튜디오가 남아 있으면 같은 요청으로 재시도한다. 스튜디오가 없으면 감사 기록을 확인하고 임의로 새 요청을 만들지 않는다.

부분 실패를 수동 SQL로 이어서 처리하지 않는다. S3 키와 감사 기록을 잃으면 삭제 범위를 검증할 수 없으므로 항상 동일 요청의 서버 절차로 복구한다.

# Mock 갤러리 샘플 템플릿 운영

`POST /api/v1/galleries/mock`은 **템플릿 갤러리** 하나를 복제해 완전히 일반적인 갤러리를
만든다. 템플릿 갤러리는 운영자 스튜디오가 일반 파이프라인(presigned 업로드 + 임베딩
Lambda)으로 시드한 진짜 갤러리이고, 서버는 `app.mock-gallery.template-gallery-id`로 그
위치만 안다. manifest도, 전용 스키마도, 전용 S3 prefix도 없다.

## 시드 절차

1. **운영자 계정 준비** — 스튜디오가 있는 계정이면 된다. 전용 운영자 계정을 권장한다:
   이 스튜디오의 갤러리 목록에 템플릿이 계속 보이고, **템플릿 갤러리를 휴지통에 버려
   물리 삭제되면 템플릿과 S3 원본이 함께 사라져 `/mock`이 503이 된다.** 운영자 스튜디오는
   인프라다.

2. **샘플 원본 확인** — AI 생성 이미지 등 저작권·초상권 문제가 없는 원본만 쓴다.
   현재 세트: `AI-Generated-WeddingPhotos` 80장(16세트 × 5장, PNG).

3. **시드 실행** — 운영자 계정의 access token으로:

   ```bash
   python3 scripts/seed-mock-template.py \
       --base-url https://api.easyselect.kr \
       --token "$ACCESS_TOKEN" \
       --directory ~/AI-Generated-WeddingPhotos
   ```

   갤러리 생성 → 업로드 URL 발급 → S3 PUT → 완료 통보 → 임베딩 실행 → `embedded == total`
   폴링까지 한다. 템플릿 갤러리는 **DRAFT로 둔다** — 열 이유가 없고, 초대할 일도 없다.

4. **검증** — `GET /api/v1/galleries/{id}/photos?size=100`으로 전 장이 EMBEDDED이고
   `previewReady`가 true인지 확인한다. preview가 없는 사진은 복제본도 원본 폴백으로
   보이므로 치명적이지는 않지만, HEIC가 아닌 이상 previewsFailed는 IAM 문제다
   (embedder/README 참조).

5. **활성화** — Parameter Store에 id를 넣고 앱을 재시작한다(기동 시 1회 로드).

   ```bash
   aws ssm put-parameter --name /wes/prod/app.mock-gallery.template-gallery-id \
       --type String --value "<갤러리 id>" --overwrite
   ```

6. **스모크** — 운영자 계정으로 `POST /api/v1/galleries/mock`을 한 번 호출한다.
   운영자 자신의 템플릿을 복제한 갤러리가 생기는 것이 정상이고, 확인 후 그대로 두거나
   무시하면 된다(일반 갤러리라 특별히 치울 것이 없다).

## 샘플 세트 교체

새 갤러리를 처음부터 시드하고(3~4), 파라미터를 새 id로 바꾸고(5), 재시작한다.
**이미 만들어진 mock 갤러리들은 건드릴 필요가 없다** — 각자 자기 키 공간에 복사본을
가지므로 옛 템플릿 갤러리를 지워도 영향이 없다. 옛 템플릿은 확인 후 운영자가 지운다
(갤러리 삭제 API가 없으므로 DB에서 행을 지우고 `galleries/{옛 id}/`·`previews/galleries/{옛 id}/`
prefix를 `aws s3 rm --recursive`로 정리한다).

## 주의

- **`scripts/reset-test-data.sh`는 템플릿도 지운다** (galleries 전체 TRUNCATE +
  `--with-s3`면 `galleries/`·`previews/` prefix 삭제). 리셋 후에는 재시드하고 파라미터를
  갱신해야 한다.
- 임베딩 모델을 바꾸면 템플릿 갤러리도 다른 갤러리처럼 `embeddings/run?force=true`로
  재계산하면 된다. 이미 만들어진 mock 갤러리들도 같은 방법으로 각자 재계산할 수 있다.
- 앱 role IAM: mock 생성은 서버가 직접 `CopyObject`를 부른다. 원본 `s3:GetObject` +
  대상 `s3:PutObject`가 필요한데, presigned GET/PUT이 같은 role로 서명되므로 보통 이미
  있다. 403이면 인프라 레포의 앱 role 정책을 확인한다.

## 구 설계 잔재 정리 (1회성)

공유 템플릿 설계(V11, `mock-gallery/v1/` prefix)를 걷어낸 뒤 남은 것들:

- prod S3의 `mock-gallery/` prefix 160객체(~178MB)는 어떤 코드도 참조하지 않는다.
  새 흐름 검증이 끝나면 삭제한다:

  ```bash
  aws s3 rm s3://<버킷>/mock-gallery/ --recursive
  ```

- V15 마이그레이션이 prod의 구 MOCK 갤러리 행을 삭제하고 스키마를 V11 이전으로
  되돌린다. **배포 순서: 임베딩 Lambda(embedder/deploy.sh) 먼저, 앱은 그다음.**
  순서가 뒤집히면 구 Lambda의 UPDATE가 사라진 `storage_ownership` 컬럼을 참조해
  임베딩 배치가 통째로 실패한다.

# Mock 갤러리 운영 자산 준비

Mock 갤러리 원본과 미리보기는 S3의 버전 고정 경로에 두고, 임베딩 manifest만 서버
classpath에 포함한다. 이미 공개한 버전은 덮어쓰거나 일부 객체를 삭제하지 않는다. 사진이나
모델·전처리 조건이 바뀌면 새 `templateVersion`을 만든다.

## v1 기준

- 원본: `organic-agent/AI-Generated-WeddingPhotos`의
  `af046f2c751999e962d26ffe8d131b2e37b54e62` 커밋, PNG 80장
- S3 버킷: `wes-photos-233927217926` (`ap-northeast-2`)
- 원본 key: `mock-gallery/v1/originals/<원본 파일명>`
- 미리보기 key: `mock-gallery/v1/previews/<원본 stem>.jpg`
- manifest: `classpath:/mock-gallery/v1.json`
- 임베딩: `facebook/dinov2-base`, 768차원, L2 정규화
- 미리보기: EXIF 방향 보정 후 RGB, 긴 변 1024 px, JPEG quality 82, optimize/progressive
- 객체 캐시: `private,max-age=31536000,immutable`

원본 저장소 README는 실제 인물·스톡 사진을 입력으로 쓰지 않은 AI 생성 가상 성인 커플이며
로고·워터마크·읽을 수 있는 문구가 없다고 명시한다. 새 버전을 공개하기 전에는 원본 커밋과
README의 이용 메모를 다시 확인하고, 실존 인물의 증언이나 실제 결혼 기록처럼 사용하지 않는다.

### v1 완료 증빙 (2026-08-11)

- 운영 Lambda와 ECR `latest` digest:
  `sha256:77fa5eca048f98c4407e9ee0fc715480d1affb6c99deb107807917d25f85289a`
- classpath manifest SHA-256:
  `b39997e2dbd6adb22a62ac35f3bedd5fc590a341977f8fc7d5191be8ea86aa8b`
- S3 exact-prefix inventory: 원본 80개 + 미리보기 80개, 총 160개·177,692,330 bytes
- 업로드 직후 검증과 별도 `--verify-only` 전수 검증 모두 통과
- 앱 role `wes-ec2-20260724015736175500000002`의 표본 `s3:GetObject`와 버킷
  `s3:ListBucket` policy simulation 결과 `allowed`
- 버킷 Public Access Block 네 항목 모두 `true`, 표본 객체 SSE-S3 `AES256`

## 1. 배포 이미지 고정

manifest 임베딩과 운영 Lambda가 같은 모델·전처리 코드를 사용해야 한다. 먼저 현재
`embedder`를 배포하고 Lambda가 실행하는 ECR digest를 기록한다.

```bash
cd embedder
./deploy.sh

aws lambda get-function \
  --function-name wes-embedder \
  --region ap-northeast-2 \
  --query 'Code.ImageUri' \
  --output text
```

Mock 갤러리를 활성화하기 전에 배포된 이미지가 `SHARED_TEMPLATE` 사진을 일반·force 임베딩
대상에서 모두 제외하는 버전인지 확인한다.

## 2. 로컬 산출물 생성

출력 디렉터리는 존재하지 않아야 한다. 생성기는 원본을 복사하지 않고 `manifest.json`과
`previews/`만 새 디렉터리에 원자적으로 공개한다.

```bash
docker run --rm --platform linux/amd64 \
  --entrypoint python \
  -v '<원본 저장소 절대 경로>:/input:ro' \
  -v '<빈 출력 부모 절대 경로>:/output' \
  '<Lambda Code.ImageUri의 digest 고정 URI>' \
  -m embedder.mock_gallery \
  --source-dir /input \
  --output-dir /output/v1 \
  --template-version v1
```

생성 완료 뒤 `manifest.json`만 `src/main/resources/mock-gallery/v1.json`으로 복사한다. 원본과
미리보기 파일은 서버 저장소나 classpath에 넣지 않는다.

## 3. 조건부 업로드와 검증

업로드 도구는 manifest 전체, 160개 로컬 파일의 SHA-256, key 경계를 먼저 검증한다. S3에는
`If-None-Match: *`로 없는 객체만 쓰고, 기존 객체의 bytes·metadata·checksum·cache policy가
다르면 실패한다. 따라서 같은 명령은 재실행할 수 있지만 기존 버전을 교체하지 않는다.

```bash
python3 scripts/upload-mock-gallery-assets.py \
  --source-dir '<원본 저장소 절대 경로>' \
  --prepared-dir '<출력 부모 절대 경로>/v1' \
  --bucket wes-photos-233927217926 \
  --region ap-northeast-2 \
  --profile default

python3 scripts/upload-mock-gallery-assets.py \
  --source-dir '<원본 저장소 절대 경로>' \
  --prepared-dir '<출력 부모 절대 경로>/v1' \
  --bucket wes-photos-233927217926 \
  --region ap-northeast-2 \
  --profile default \
  --verify-only
```

두 번째 명령은 `mock-gallery/v1/` 아래 객체가 manifest의 160개와 정확히 같고, 각 객체가
다음 조건을 모두 만족해야 성공한다.

- 원본 `Content-Type`은 manifest 값, 미리보기는 `image/jpeg`
- `Cache-Control`은 `private,max-age=31536000,immutable`
- S3 SHA-256 checksum과 `sha256` metadata가 manifest와 일치
- `template-version` metadata가 `v1`

## 4. 읽기 권한과 활성화

앱 instance role에 버킷의 `s3:GetObject`와 `s3:ListBucket`이 있는지 실제 role ARN으로
확인한다. 버킷의 Block Public Access는 유지하고 객체를 public-read로 만들지 않는다.

S3 검증, Lambda digest 확인, 서버 manifest 테스트가 모두 끝난 뒤에만 prod 프로필에서 두
설정을 함께 활성화한다.

```yaml
app:
  mock-gallery:
    enabled: true
    manifest-location: "classpath:/mock-gallery/v1.json"
```

배포 후 인증된 사진작가 계정으로 `POST /api/v1/galleries/mock`을 한 번 호출하고, 응답의
`templateVersion`이 `v1`인지와 클러스터 목록의 미리보기 presigned URL이 200인지 확인한다.
같은 계정의 반복 호출은 기존 Mock 갤러리를 반환해야 한다.

## 실패와 롤백

- 생성 또는 업로드 검증 실패: prod gate를 켜지 않고 원인부터 수정한다.
- 일부 업로드 뒤 실패: 같은 명령을 재실행한다. 수동 덮어쓰기·삭제로 맞추지 않는다.
- 기존 S3 객체 불일치: `v1`을 수정하지 말고 원인을 조사한 뒤 새 버전을 만든다.
- 배포 후 생성 API 또는 미리보기 실패: `app.mock-gallery.enabled=false`로 되돌린다. 이미 생성된
  갤러리와 공유 S3 객체를 자동 삭제하지 않는다.

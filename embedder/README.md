# embedder

갤러리 하나의 사진을 DINOv2로 임베딩해 `photos.embedding`(pgvector `vector(768)`)에 적재한다.
Lambda로 배포되지만 **로컬에서도 같은 코드가 그대로 돈다** — 진입점만 다르다.

```
handler.py    Lambda      event {"galleryId": 1, "force": false}
__main__.py   로컬 CLI    python -m embedder --gallery-id 1
     └─────── 둘 다 job.run() 하나를 부른다
```

## 흐름

```
SELECT id, storage_key FROM photos
WHERE gallery_id = ? AND status <> 'PENDING' AND embedding IS NULL
  → S3 GET → HEIC 디코드 · EXIF 회전 · 리사이즈 → DINOv2(L2 정규화)
  → UPDATE photos SET embedding = ?, status = 'EMBEDDED'
```

- **재실행이 안전하다.** 기본 조건이 `embedding IS NULL`이라 중간에 죽어도 다시 부르면 남은
  것만 이어서 한다. 한 장이 실패해도 잡을 죽이지 않고 `failed`에 키만 모아 돌려준다.
- **`--force`는 이미 채워진 것까지 다시 계산한다.** 모델이나 전처리를 바꿔 전량 재계산할 때만.
- **`PENDING`은 건너뛴다.** 업로드 URL만 발급되고 S3에 객체가 없을 수 있는 상태다.

## 접속: 비밀번호가 없다

DB 접속은 **RDS IAM 인증**이다. `boto3`가 만드는 15분짜리 토큰을 비밀번호 자리에 넣는다.
토큰 생성은 로컬 서명 연산이라 네트워크를 타지 않는데, 이 함수가 붙는 서브넷에는 NAT도
인터페이스 엔드포인트도 없으므로 그 점이 결정적이다 — Parameter Store를 읽으려면 시간당
과금되는 엔드포인트가 두 개(ssm, kms) 필요해진다.

부수 효과로 비밀번호가 Lambda 환경변수에도 Terraform state에도 남지 않는다.

전제 조건 두 가지는 인프라 레포가 책임진다:

- RDS 인스턴스에 `iam_database_authentication_enabled = true`
- Lambda 롤에 `rds-db:connect`

그리고 **DB 안에 사용자를 한 번 만들어 줘야 한다**(Terraform이 못 하는 부분).
인프라 레포 `docs/runbook.md`의 "임베딩 파이프라인" 절을 볼 것:

```sql
CREATE USER embedder;
GRANT rds_iam TO embedder;
GRANT SELECT, UPDATE ON photos TO embedder;
```

## 로컬 실행

RDS는 퍼블릭 접근이 없으므로 SSM 포트 포워딩으로 터널을 먼저 연다.

```bash
aws ssm start-session --region ap-northeast-2 \
  --target "$(cd ../../../organic-agent-infrastructure && terraform output -raw ec2_instance_id)" \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters '{"host":["<rds-endpoint>"],"portNumber":["5432"],"localPortNumber":["15432"]}'
```

다른 탭에서:

```bash
python -m venv .venv && source .venv/bin/activate
# torchvision까지 받아야 한다. transformers의 fast 이미지 프로세서가 요구한다.
pip install torch torchvision --index-url https://download.pytorch.org/whl/cpu
pip install -r requirements.txt

export DB_HOST=localhost DB_PORT=15432 DB_NAME=wes_db DB_USER=embedder
# 토큰은 RDS 엔드포인트로 서명해야 RDS가 받아준다. 연결은 터널(localhost)로 하지만
# 서명 대상은 실제 호스트다.
export DB_AUTH_HOST="<rds-endpoint>"
# 터널을 거치면 인증서의 호스트명이 localhost와 맞지 않아 verify-full이 실패한다.
export DB_SSLMODE=require
export S3_BUCKET="$(cd ../../../organic-agent-infrastructure && terraform output -raw photo_bucket)"

python -m embedder --gallery-id 1
```

맥에서는 `torch.backends.mps`가 잡혀 GPU로 돈다. Lambda는 CPU다.

## 빌드와 배포

```bash
REPO="$(cd ../../../organic-agent-infrastructure && terraform output -raw embedder_repository_url)"
aws ecr get-login-password --region ap-northeast-2 \
  | docker login --username AWS --password-stdin "${REPO%%/*}"

docker buildx build --platform linux/amd64 --provenance=false --sbom=false \
  -t "$REPO:latest" --push .

aws lambda update-function-code --region ap-northeast-2 \
  --function-name "$(cd ../../../organic-agent-infrastructure && terraform output -raw embedder_function_name)" \
  --image-uri "$REPO:latest"
```

세 플래그가 전부 필요하다.

- **`--platform linux/amd64`** — 맥에서 빌드하면 기본이 arm64다. Lambda 함수는 x86_64로
  만들어져 있어서 아키텍처가 다르면 실행 시점에 죽는다. (앱 컨테이너는 Graviton EC2에
  올라가 arm64지만, 이 함수는 별개다.)
- **`--provenance=false --sbom=false`** — 이게 없으면 buildx가 attestation이 붙은 **manifest
  list**를 만들고, Lambda는 그걸 거절한다:

  ```
  InvalidParameterValueException: The image manifest, config or layer media type
  for the source image ... is not supported.
  ```

  이미지 내용과 무관한 포장 형식 문제인데 메시지가 그렇게 읽히지 않는다. Lambda는 단일
  아키텍처 Docker v2 매니페스트만 받는다.

**첫 apply는 순서가 있다.** Lambda는 이미지가 없는 ECR을 상대로 만들어지지 않으므로,
리포지토리만 먼저 만들고 → 푸시 → 전체 apply 한다. 인프라 레포 `docs/deploy-order.md` 참고.

## 환경변수

| 변수 | 기본값 | 비고 |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` | — | 필수(포트 기본 5432). Lambda는 Terraform이 주입 |
| `DB_AUTH_HOST` | `DB_HOST` | IAM 토큰에 서명할 호스트. 터널로 로컬 실행할 때만 다르다 |
| `DB_SSLMODE` | `verify-full` | 터널로 로컬 실행할 때는 `require` |
| `DB_SSLROOTCERT` | `/opt/rds-ca/global-bundle.pem` | 이미지에 구워져 있다 |
| `S3_BUCKET` | — | 필수 |
| `EMBED_DIM` | `768` | `vector(n)` 컬럼과 `Photo.EMBEDDING_DIMENSION`과 셋이 같아야 한다 |
| `EMBED_BATCH_SIZE` | `8` | 모델에 한 번에 넣는 장수 |
| `EMBED_MODEL_ID` | `facebook/dinov2-base` | 바꾸면 이미지를 다시 빌드해야 한다(가중치가 구워져 있다) |
| `RESIZE_LONG_EDGE` | `1024` | 디코딩 직후 메모리를 누르는 용도 |

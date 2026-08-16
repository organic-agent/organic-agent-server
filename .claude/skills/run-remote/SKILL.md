---
name: run-remote
description: |
  로컬 서버를 원격 RDS(실제 갤러리 데이터)에 붙여 띄우고, JWT를 직접 발급해 API를 호출해 실데이터로 동작을 확인한다.
  Trigger: "실제 갤러리로 확인해보자", "실데이터로 API 확인", "bootRun으로 실갤러리 결과 확인", "운영 DB로 띄워줘"
  Do NOT use for: 통합 테스트 실행(직접 gradle), 로컬 DB 개발(docker-compose), 운영 배포 확인(배포 파이프라인)
  Boundary: 원격 DB에는 읽기 전용으로만 접근한다. 쓰기 API 호출·데이터 변경은 이 스킬 범위 밖이며, 필요하면 사용자에게 명시적으로 확인받아야 한다.
allowed-tools: Bash, Read
---

# 실갤러리 검증 (원격 RDS + bootRun)

실제 갤러리(임베딩·EXIF가 채워진 데이터)는 원격 RDS에만 있다. 로컬 프로필의 DB는
`localhost:5432`(대개 꺼져 있음)이고, 로컬에는 임베딩 Lambda가 없어 새로 올린 사진이
임베딩되지 않는다. 그래서 실데이터 확인은 **SSM 터널 + datasource 오버라이드**로 한다.

전제: AWS 자격증명(`aws sts get-caller-identity`), Docker(psql 대용), 8080·15432 포트 비어 있음.

## Phase 1: 안전 확인 — Flyway 이력 대조 (건너뛰기 금지)

bootRun은 기동 시 Flyway를 실행한다. **저장소에 원격 DB보다 새 마이그레이션이 있으면
그 마이그레이션이 운영 DB에 실제로 실행된다.** 반드시 먼저 대조하라:

```bash
ls src/main/resources/db/migration/ | sort -V | tail -3   # 저장소 최신 버전
```

터널을 먼저 열고(Phase 2) 아래로 DB 쪽 이력을 확인:

```bash
export PGPASSWORD=$(aws ssm get-parameter --region ap-northeast-2 \
  --name /wes/prod/spring.datasource.password --with-decryption \
  --query Parameter.Value --output text)
docker run --rm -e PGPASSWORD pgvector/pgvector:pg16 psql \
  -h host.docker.internal -p 15432 -U wes_admin -d wes_db -t \
  -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
```

- 최신 버전이 일치하고 success=t → 진행 (Flyway는 no-op).
- **저장소가 더 새 버전을 갖고 있으면 중단**하고 사용자에게 보고하라. 운영 DB에
  마이그레이션을 먼저 태울지는 사용자 결정이다.
- 비밀번호는 항상 env(`PGPASSWORD`)로만 전달하고 echo하지 마라.

## Phase 2: SSM 터널

```bash
# 백그라운드로 실행 (Bash run_in_background)
bash scripts/db-tunnel.sh 2>&1 | grep -v "Password"
```

`nc -z localhost 15432`가 열릴 때까지 대기(수 초). 터널은 유휴 시 끊길 수 있다 —
연결 실패하면 다시 띄우면 된다. 접속 정보: host `localhost:15432` (Docker 안에서는
`host.docker.internal:15432`), db `wes_db`, user `wes_admin`, sslmode `require`.

## Phase 3: 서버 기동 (datasource 오버라이드)

기본 프로필이 없으므로 `local`을 명시해야 시크릿(JWT·OAuth)이 Parameter Store에서 온다.
datasource는 env 변수로 덮는다 — Spring 우선순위가 env > config-data(Parameter Store)라
`/wes/local/`의 localhost:5432를 이긴다.

```bash
# 백그라운드로 실행
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:15432/wes_db?sslmode=require"
export SPRING_DATASOURCE_USERNAME="wes_admin"
export SPRING_DATASOURCE_PASSWORD=$(aws ssm get-parameter --region ap-northeast-2 \
  --name /wes/prod/spring.datasource.password --with-decryption \
  --query Parameter.Value --output text)
./gradlew bootRun --args="--spring.profiles.active=local" 2>&1 | grep -viE "password"
```

`nc -z localhost 8080`이 열릴 때까지 대기(30초 내외).

실험용 파라미터가 필요하면 `--args`에 커맨드라인 프로퍼티를 추가한다(우선순위 최상).
예: 클러스터 레벨 3과 같은 번들에 kNN만 끈 임시 레벨 6으로 전/후 비교 —

```
--app.cluster.levels.6.strict-threshold=0.92 --app.cluster.levels.6.lenient-threshold=0.82
--app.cluster.levels.6.window-seconds=90 --app.cluster.levels.6.knn-k=0
```

## Phase 4: JWT 직접 발급

access token은 무상태(HS256, DB 미조회)라 로컬 시크릿으로 직접 서명하면 된다.
클레임: `sub`(userId 문자열), `type=ACCESS`, `providerId`, `role` — users 테이블에서 확인.
부부 계정 `popora99@gmail.com`(user id 3)이 실갤러리 1·2 모두 접근 가능하다.

```bash
TOKEN=$(python3 - <<'EOF'
import base64, hashlib, hmac, json, time, uuid
def b64(d): return base64.urlsafe_b64encode(d).rstrip(b"=")
secret = b"local-development-only-secret-key-do-not-use-in-production"  # /wes/local/jwt.secret
header = b64(json.dumps({"alg":"HS256","typ":"JWT"}).encode())
now = int(time.time())
payload = b64(json.dumps({
    "sub":"3","jti":str(uuid.uuid4()),
    "type":"ACCESS","providerId":"106289367505666640786","role":"USER",
    "iat":now,"exp":now+3600}).encode())
msg = header + b"." + payload
sig = b64(hmac.new(secret, msg, hashlib.sha256).digest())
print((msg + b"." + sig).decode())
EOF
)
```

토큰을 파일로 남겼다면 scratchpad에만 두고 Phase 6에서 지운다.

## Phase 5: API 호출

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/galleries/1/photo-clusters?level=3"
```

- 실갤러리: 1(본식 743장), 2(57장) — 둘 다 임베딩·EXIF 완비. 4·5는 목/샘플.
- **GET만 호출한다.** 운영 데이터가 뒤에 있다 — 쓰기 API(업로드·삭제·셀렉 확정 등)는
  이 스킬로 부르지 않는다.
- 응답이 크면 python으로 요약해서 보라(묶음 수·크기 분포 등).

## Phase 6: 정리

1. TaskStop으로 bootRun과 터널 태스크를 순서대로 종료.
2. 토큰 파일 삭제.
3. `nc -z localhost 8080`, `nc -z localhost 15432` 둘 다 닫혔는지 확인.

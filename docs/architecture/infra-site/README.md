# infra-site — wes 인프라 클릭형 아키텍처 문서

정적 HTML 일곱 장 + 공용 CSS + 흐름도 렌더러(`diagram.js`) + draw.io 구성도 PNG. 서버·빌드 없이 `index.html` 을 브라우저로 열면 된다.
형식과 편집법은 `../pipeline-v2-site/README.md` 와 같다(`site.css`·`diagram.js` 는 그쪽에서 복사한 것).

```bash
open docs/architecture/infra-site/index.html
```

## 페이지

| 파일 | 번호 | 내용 |
|---|---|---|
| `index.html` | 1 | draw.io 구성도 원본 + 클릭형 전체 지도(외부 · 리전 서비스 · public 서브넷 · DB 서브넷) + 경로 여섯 줄 + 인벤토리 + 설계 원칙 |
| `network.html` | 2 | VPC · 서브넷 넷 · 라우트 · VPC 엔드포인트 · 보안 그룹 여섯과 규칙 전수 · 인바운드 매트릭스 · DNS 스택 분리 · Route 53 레코드 |
| `request.html` | 3 | Route 53 → ACM → ALB(리스너 · 404 규칙 · 타깃) → wes-app EC2(스펙 · user_data · 롤 정책) · 부팅 시 읽는 SSM 값 |
| `data.html` | 4 | RDS 설정 · DB 계정 넷과 커넥션 상한 · 비밀번호 ephemeral 처리 · S3 버킷 셋 · CORS · 키 구조와 prefix별 권한 |
| `ai.html` | 5 | Lambda 셋 스펙 · IAM · 비동기 설정 · 알람 · ECR · GPU 워커 풀(롤 · systemd · 정지 4겹) · Image Builder AMI · Bedrock |
| `admin.html` | 6 | Tailscale grant · Serve · wes-admin 호스트 · Caddy DNS-01 · Docker 네트워크 둘 · BackOffice · admin-api · SSM prefix |
| `ops.html` | 7 | 모니터링(Loki · Grafana · Caddy) · 테스트 프론트 · OIDC 롤 일곱 · 인프라 CI/CD · state · 배포 순서 · SSM prefix 다섯 · 알람 · 비용 |

`wes-infrastructure-architecture.png` 는 `organic-agent-infra/docs/wes-infrastructure-architecture.drawio` 를 내보낸 것이다. 그림을 고치면 인프라 repo 의 drawio 를 고치고 PNG 를 다시 복사한다.

## 사실의 출처

2026-09-09, `organic-agent-infra` main@42b33c6 의 `variables.tf` 기본값과 모듈 코드를 읽어 썼다. 절차·상한 같은 운영 값은 `docs/runbook.md` 를 따랐고, 문서 간 불일치(`photoselect` 커넥션 상한 8 vs 24)는 페이지 안에 표시했다. 인프라 코드가 바뀌면 해당 페이지의 "코드 위치" 표와 수치부터 고친다.

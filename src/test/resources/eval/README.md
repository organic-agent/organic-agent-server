# 클러스터링 평가셋

유사 사진 클러스터링 품질을 pairwise precision/recall/F1로 측정하기 위한 정답 데이터.
실제 갤러리를 사람이 라벨링한 것이며, 이미지 바이트는 포함하지 않는다 — 클러스터링의
입력은 임베딩과 촬영 시각뿐이므로 그 둘과 정답 라벨만 fixture로 담는다.

배경과 전체 계획: `docs/plans/clustering-quality-improvement.md` (로컬, gitignore 대상).

## 라벨링 기준

라벨 단위는 **"셀렉에서 서로 대체재인 묶음"** 이다. 부부가 그 묶음에서 한두 장만
고르면 되는 사진들이 같은 그룹이다.

| 상황 | 판정 |
|---|---|
| 같은 포즈 연사(burst) | 같은 그룹 |
| 같은 장면·같은 컨셉에서 카메라 거리(줌/풀샷)나 포즈만 약간 다름 | **같은 그룹** — 과분할 실패 사례의 핵심, 반드시 이렇게 라벨 |
| 같은 장소지만 컨셉·의상·인물 구성이 바뀜 | 다른 그룹 |
| 어디에도 안 묶이는 단독 사진 | 라벨 비움 (싱글턴 처리) |
| 애매하면 | **다른 그룹** — 보수적으로. 기준이 흔들리면 지표가 관대해진다 |

라벨 값은 자유 문자열이다 (예: `scene-01`, `bouquet-burst`). 그룹 간 이름의 의미는
없고 같은 문자열 = 같은 그룹이라는 사실만 쓰인다.

## 평가 갤러리 선정 기준

- 두 실패 모드의 실측 사례를 포함할 것:
  - **과병합**: 다리 사진 한 장 때문에 다른 두 장면이 합쳐졌던 갤러리
  - **과분할**: 같은 장면인데 거리/포즈 차이로 안 묶였던 쌍이 있는 갤러리
- EXIF 촬영 시각이 있는 사진과 없는 사진이 섞여 있으면 더 좋다 (시간 게이트의
  폴백 경로도 측정된다).

## fixture 형식

`gallery-<id>.json` (갤러리당 1파일):

```json
{
  "galleryId": 123,
  "embeddingDimension": 768,
  "photos": [
    {
      "photoId": 1,
      "fileName": "DSC01234.jpg",
      "takenAt": "2026-05-01T13:00:00",
      "groupLabel": "scene-01",
      "embedding": [0.012, -0.034, "…768개"]
    }
  ]
}
```

- `takenAt`: EXIF `DateTimeOriginal` (타임존 없음 — `PhotoMetadata.takenAt`과 동일 의미).
  없으면 `null`.
- `groupLabel`: `null`이면 싱글턴 — 평가 시 자기 혼자만의 그룹으로 취급한다.
- 임베딩이 재생성되면(모델·풀링·해상도 변경) fixture도 다시 export해야 한다.
  라벨 CSV는 photo id 기준이라 재사용된다.

## 만드는 절차

```bash
# 0. 터널 (원격 DB 기준. 로컬 DB면 생략하고 --host/--port 지정)
scripts/db-tunnel.sh                      # 비밀번호가 클립보드에 복사된다

# 1. 라벨링 템플릿 CSV 생성 (촬영 시각순 정렬 — 연사가 붙어 나온다)
PGPASSWORD=$(pbpaste) scripts/export-eval-set.py template --gallery-id 123

# 2. 생성된 eval-labels-123.csv의 group_label 열을 사람이 채운다
#    (갤러리 화면이나 원본 파일을 보면서. 위 라벨링 기준을 따를 것)

# 3. fixture 생성 → 이 디렉토리에 커밋
PGPASSWORD=$(pbpaste) scripts/export-eval-set.py fixture \
    --gallery-id 123 --labels eval-labels-123.csv
```

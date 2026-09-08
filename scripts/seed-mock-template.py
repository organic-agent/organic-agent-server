#!/usr/bin/env python3
"""Mock 갤러리의 샘플 템플릿 갤러리를 일반 API로 시드한다.

서버에 특별한 경로는 없다 — 운영자 계정으로 갤러리를 만들고, presigned URL로 원본을
올리고, 임베딩을 실행하는 보통의 흐름 그대로다. 끝나면 갤러리 id를 출력하며, 그 값을
Parameter Store(`/wes/prod/app.mock-gallery.template-gallery-id`)에 넣고 앱을 재시작하면
`POST /api/v1/galleries/mock`이 활성화된다. 자세한 절차는 docs/runbooks/mock-gallery-template.md.

사용:
  python3 scripts/seed-mock-template.py \
      --base-url https://api.example.com \
      --token "$ACCESS_TOKEN" \
      --directory ~/wedding-samples

  # 업로드 도중 실패했다면 같은 갤러리로 이어서 (사진이 뒤에 덧붙는 것이 아니라
  # 새로 발급된 키로 다시 올라가므로, 깨끗이 하려면 새 갤러리로 처음부터 다시)
  python3 scripts/seed-mock-template.py ... --gallery-id 123
"""

from __future__ import annotations

import argparse
import base64
import json
import mimetypes
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

# PhotoService.ALLOWED_CONTENT_TYPES와 같은 목록이다. 여기 없는 파일은 서버가 400으로 거절한다.
CONTENT_TYPES = {
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".webp": "image/webp",
    ".heic": "image/heic",
    ".heif": "image/heif",
}

POLL_INTERVAL_SECONDS = 10
POLL_TIMEOUT_SECONDS = 15 * 60

# CRC32C(Castagnoli, 반사 다항식 0x82F63B78) 테이블. S3 x-amz-checksum-crc32c 는 이 값 4바이트를 base64로 적는다.
_CRC32C_TABLE = []
for _n in range(256):
    _c = _n
    for _ in range(8):
        _c = (_c >> 1) ^ 0x82F63B78 if _c & 1 else _c >> 1
    _CRC32C_TABLE.append(_c)


def main() -> int:
    args = parse_args()
    files = collect_files(args.directory)
    if not files:
        print(f"오류: {args.directory} 에 업로드할 이미지가 없다", file=sys.stderr)
        return 1
    print(f"이미지 {len(files)}장: {args.directory}")

    api = Api(args.base_url, args.token)

    gallery_id = args.gallery_id
    if gallery_id is None:
        gallery = api.post("/api/v1/galleries", {"title": args.title})
        gallery_id = gallery["id"]
        print(f"템플릿 갤러리 생성: id={gallery_id} (DRAFT로 둔다 — 열 이유가 없다)")
    else:
        print(f"기존 갤러리에 이어서: id={gallery_id}")

    # 바이트 수와 CRC32C 는 서명에 들어간다 — 발급 요청에 적은 값 그대로 PUT 헤더로 보내야 한다.
    bodies = {f: f.read_bytes() for f in files}
    checksums = {f: crc32c_base64(bodies[f]) for f in files}
    uploads = api.post(
        f"/api/v1/galleries/{gallery_id}/photos/upload-urls",
        {
            "files": [
                {
                    "fileName": f.name,
                    "contentType": CONTENT_TYPES[f.suffix.lower()],
                    "contentLength": len(bodies[f]),
                    "crc32c": checksums[f],
                }
                for f in files
            ],
        },
    )["uploads"]

    for file, upload in zip(files, uploads):
        put_object(upload["uploadUrl"], file, bodies[file], CONTENT_TYPES[file.suffix.lower()], checksums[file])
    print(f"S3 업로드 완료: {len(files)}장")

    api.post(
        f"/api/v1/galleries/{gallery_id}/photos/complete",
        {"photoIds": [u["photoId"] for u in uploads]},
    )

    # 임베딩만 따로 도는 API 는 없다 — AI 분석(FULL)의 첫 단계가 임베딩이다. 템플릿은 벡터까지만 필요하므로
    # summary 의 embedded 로 기다리고, 점수·그룹 단계는 그 뒤에 알아서 이어진다.
    run = api.post(f"/api/v1/galleries/{gallery_id}/ai-analysis", {"mode": "FULL"})
    print(f"AI 분석 접수: jobId={run['jobId']} stage={run['stage']}")

    wait_until_embedded(api, gallery_id)
    print()
    print(f"완료. 템플릿 갤러리 id = {gallery_id}")
    print("다음 단계: Parameter Store의 app.mock-gallery.template-gallery-id 에 이 값을 넣고 앱을 재시작한다.")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Mock 갤러리 샘플 템플릿 시드")
    parser.add_argument("--base-url", required=True, help="API 서버 (예: https://api.example.com)")
    parser.add_argument("--token", required=True, help="운영자 계정의 access token")
    parser.add_argument("--directory", required=True, type=Path, help="샘플 원본 디렉토리")
    parser.add_argument("--gallery-id", type=int, help="실패 후 재개할 기존 갤러리 id")
    parser.add_argument("--title", default="MOCK TEMPLATE — 열지 말 것", help="템플릿 갤러리 제목")
    return parser.parse_args()


def collect_files(directory: Path) -> list[Path]:
    return sorted(f for f in directory.iterdir() if f.is_file() and f.suffix.lower() in CONTENT_TYPES)


def crc32c_base64(data: bytes) -> str:
    crc = 0xFFFFFFFF
    for byte in data:
        crc = _CRC32C_TABLE[(crc ^ byte) & 0xFF] ^ (crc >> 8)
    crc ^= 0xFFFFFFFF
    return base64.b64encode(crc.to_bytes(4, "big")).decode("ascii")


def put_object(url: str, file: Path, body: bytes, content_type: str, crc32c: str) -> None:
    # Content-Type·Content-Length·x-amz-checksum-crc32c 셋이 서명에 들어 있다. 하나라도 다르면 S3가 403을 돌려주고,
    # 체크섬이 바이트와 안 맞으면 400(BadDigest)이다. 다른 x-amz-* 헤더는 서명에 없으니 붙이지 않는다.
    request = urllib.request.Request(url, data=body, method="PUT")
    request.add_header("Content-Type", content_type)
    request.add_header("Content-Length", str(len(body)))
    request.add_header("x-amz-checksum-crc32c", crc32c)
    with urllib.request.urlopen(request) as response:
        if response.status not in (200, 204):
            raise RuntimeError(f"S3 PUT 실패: {file.name} → {response.status}")


def wait_until_embedded(api: "Api", gallery_id: int) -> None:
    # 임베딩은 비동기다(Lambda EVENT 호출). summary의 embedded가 total과 같아질 때까지 기다린다.
    deadline = time.monotonic() + POLL_TIMEOUT_SECONDS
    while True:
        summary = api.get(f"/api/v1/galleries/{gallery_id}/photos/summary")
        print(f"  임베딩 진행: {summary['embedded']}/{summary['total']}")
        if summary["total"] > 0 and summary["embedded"] == summary["total"]:
            return
        if time.monotonic() > deadline:
            raise RuntimeError(
                "임베딩 완료 대기 시간 초과. Lambda 로그를 확인하고, 남은 사진은 "
                "ai-analysis(FULL)를 다시 호출하면 이어서 처리된다(force 불필요)."
            )
        time.sleep(POLL_INTERVAL_SECONDS)


class Api:
    def __init__(self, base_url: str, token: str):
        self.base_url = base_url.rstrip("/")
        self.token = token

    def get(self, path: str) -> dict:
        return self._request("GET", path, None)

    def post(self, path: str, body: dict | None) -> dict:
        return self._request("POST", path, body)

    def _request(self, method: str, path: str, body: dict | None) -> dict:
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base_url + path, data=data, method=method)
        request.add_header("Authorization", f"Bearer {self.token}")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request) as response:
                return json.loads(response.read() or "{}")
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")
            raise RuntimeError(f"{method} {path} → {e.code}: {detail}") from e


if __name__ == "__main__":
    sys.exit(main())

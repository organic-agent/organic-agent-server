"""부하 재생 — 실제 업로드 기록(도착 시각표)대로 dev 갤러리 N개에 사진을 "도착"시킨다.

브라우저가 하는 세 단계를 그대로 따른다. 다만 S3 PUT 대신 씨앗 폴더의 사진을 서버 쪽 복사(CopyObject)한다 —
노트북 한 대의 회선으로 5명의 업로드를 흉내 낼 수 없기 때문이다(계획: docs/plans/2026-10-08/upload-5x-10min/02-baseline.md 2.2).

    ① POST /api/v1/galleries/{id}/photos/upload-urls  → PENDING 행, 저장 키
    ② S3 CopyObject  load-seed/<원본>/<파일> → 발급된 키 (시각표의 uploaded_at 오프셋에)
    ③ POST /api/v1/galleries/{id}/photos/complete     → UPLOADED
    마지막 사진 뒤 POST /api/v1/galleries/{id}/ai-analysis (웹의 "업로드 큐가 비면 잡 요청" 자리)

subcommands
    studio  재생용 dev 스튜디오를 만든다 — 부른 사용자가 OWNER (dev에 스튜디오 워크스페이스가 없을 때 한 번)
    seed  운영 원본 갤러리의 사진을 dev 버킷 씨앗 폴더로 복사하고 시각표(JSON)를 만든다 (scripts/load/upload-5x-10min/seed-dev.sh 가 부른다)
    run   시각표대로 갤러리 N개를 재생한다 (scripts/load/upload-5x-10min/replay.sh 가 부른다)

dev 전용이다. 운영 API·버킷에는 쓰지 않는다(seed 는 운영 버킷을 읽기만 한다).
"""

from __future__ import annotations

import argparse
import base64
import csv
import hashlib
import hmac
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field

import boto3
from botocore.config import Config

REGION = "ap-northeast-2"
DEV_API = "https://dev.api.easyselect.kr"
SEED_PREFIX = "load-seed"
ISSUE_BATCH = 100          # 웹이 업로드 URL을 받는 묶음 크기
COMPLETE_FLUSH_SECONDS = 1.0
COMPLETE_MAX_BATCH = 100



def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def ssm(name: str, decrypt: bool = False) -> str:
    client = boto3.client("ssm", region_name=REGION)
    return client.get_parameter(Name=name, WithDecryption=decrypt)["Parameter"]["Value"]


def s3_client():
    return boto3.client("s3", region_name=REGION,
                        config=Config(max_pool_connections=128, retries={"max_attempts": 6, "mode": "standard"}))


# ─────────────────────────────── studio ───────────────────────────────

def cmd_studio(args: argparse.Namespace) -> None:
    if "dev" not in args.api:
        sys.exit("dev 전용이다 — API가 dev 여야 한다")
    secret = ssm("/wes/dev/jwt.secret", decrypt=True)
    api = Api(args.api, mint_jwt(secret, args.user_id, args.role, args.provider_id, ttl_seconds=600))
    _, body = api.post("/api/v1/studios", {"name": args.name, "galleryUrl": args.gallery_url}, attempts=1)
    wid = body.get("workspaceId") or body.get("id")
    log(f"스튜디오 만듦 — 응답 {json.dumps(body, ensure_ascii=False)[:300]}")
    print(f"\n재생 인자: --workspace-id {wid} --user-id {args.user_id} --role {args.role} --provider-id {args.provider_id}")


# ─────────────────────────────── seed ───────────────────────────────

def cmd_seed(args: argparse.Namespace) -> None:
    src_bucket = ssm("/wes/prod/app.storage.bucket")
    dst_bucket = ssm("/wes/dev/app.storage.bucket")
    if "dev" not in dst_bucket:
        sys.exit(f"씨앗 대상이 dev 버킷이 아니다: {dst_bucket}")
    s3 = s3_client()

    with open(args.csv, newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f, fieldnames=["storage_key", "file_name", "content_type", "display_order",
                                                    "issue_offset", "upload_offset"]))
    log(f"원본 {args.source} · {len(rows)}장 · {src_bucket} → {dst_bucket}/{SEED_PREFIX}/{args.source}/")

    def one(row: dict) -> dict:
        key = row["storage_key"]
        seed_key = f"{SEED_PREFIX}/{args.source}/{key.rsplit('/', 1)[-1]}"
        head = s3.head_object(Bucket=src_bucket, Key=key, ChecksumMode="ENABLED")
        size, crc = head["ContentLength"], head.get("ChecksumCRC32C")
        try:
            existing = s3.head_object(Bucket=dst_bucket, Key=seed_key)
            copied = existing["ContentLength"] != size
        except s3.exceptions.ClientError:
            copied = True
        if copied:
            s3.copy_object(CopySource={"Bucket": src_bucket, "Key": key}, Bucket=dst_bucket, Key=seed_key,
                           ContentType=row["content_type"].lower(), MetadataDirective="REPLACE",
                           ChecksumAlgorithm="CRC32C")
        if not crc:  # 브라우저 업로드는 CRC32C를 서명하므로 보통 있다. 없으면 복사본에서 읽는다.
            crc = s3.head_object(Bucket=dst_bucket, Key=seed_key, ChecksumMode="ENABLED").get("ChecksumCRC32C")
        return {
            "seed_key": seed_key,
            "file_name": row["file_name"],
            "content_type": row["content_type"].lower(),
            "size": size,
            "crc32c": crc,
            "display_order": int(row["display_order"]),
            "issue_offset": float(row["issue_offset"]),
            "upload_offset": float(row["upload_offset"]),
        }

    photos, done = [], 0
    with ThreadPoolExecutor(max_workers=32) as pool:
        for fut in as_completed([pool.submit(one, r) for r in rows]):
            photos.append(fut.result())
            done += 1
            if done % 500 == 0 or done == len(rows):
                log(f"씨앗 {done}/{len(rows)}")

    photos.sort(key=lambda p: (p["upload_offset"], p["display_order"]))
    missing = [p for p in photos if not p["crc32c"]]
    if missing:
        sys.exit(f"CRC32C를 구하지 못한 사진 {len(missing)}장 — 시각표를 쓰지 않는다")
    timetable = {
        "source_gallery": args.source,
        "bucket": dst_bucket,
        "photos": photos,
        "upload_span_seconds": max(p["upload_offset"] for p in photos),
        "total_bytes": sum(p["size"] for p in photos),
    }
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(timetable, f, ensure_ascii=False)
    log(f"시각표 {args.out} · {len(photos)}장 · 업로드 {timetable['upload_span_seconds']:.0f}초 · "
        f"{timetable['total_bytes'] / 1e9:.2f}GB")


# ─────────────────────────────── run ───────────────────────────────

def mint_jwt(secret: str, user_id: int, role: str, provider_id: str, ttl_seconds: int) -> str:
    def b64(data: bytes) -> str:
        return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

    now = int(time.time())
    header = b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = b64(json.dumps({
        "sub": str(user_id), "jti": str(uuid.uuid4()), "type": "ACCESS", "role": role,
        "providerId": provider_id, "iat": now, "exp": now + ttl_seconds,
    }, separators=(",", ":")).encode())
    signing = f"{header}.{payload}".encode()
    sig = b64(hmac.new(secret.encode(), signing, hashlib.sha256).digest())
    return f"{header}.{payload}.{sig}"


class Api:
    def __init__(self, base: str, token: str):
        self.base, self.token = base.rstrip("/"), token

    def post(self, path: str, body: dict | None, attempts: int = 4) -> tuple[int, dict]:
        data = json.dumps(body if body is not None else {}).encode()
        for i in range(attempts):
            req = urllib.request.Request(self.base + path, data=data, method="POST", headers={
                "Authorization": f"Bearer {self.token}", "Content-Type": "application/json"})
            try:
                with urllib.request.urlopen(req, timeout=30) as res:
                    raw = res.read()
                    return res.status, (json.loads(raw) if raw else {})
            except urllib.error.HTTPError as e:
                detail = e.read().decode(errors="replace")[:300]
                if e.code < 500 or i == attempts - 1:
                    raise RuntimeError(f"POST {path} → {e.code} {detail}") from None
            except (urllib.error.URLError, TimeoutError) as e:
                if i == attempts - 1:
                    raise RuntimeError(f"POST {path} → {e}") from None
            time.sleep(0.5 * (2 ** i))
        raise AssertionError


@dataclass
class Slot:
    """시각표의 사진 한 장이 한 갤러리에서 거치는 상태."""
    photo: dict
    key_ready: threading.Event = field(default_factory=threading.Event)
    photo_id: int | None = None
    storage_key: str | None = None
    copy_late: float = 0.0


@dataclass
class GalleryRun:
    index: int
    gallery_id: int
    t0: float                       # 이 갤러리 시계의 0초 (epoch)
    slots: list[Slot]
    completed: int = 0
    copy_errors: int = 0
    issue_late_max: float = 0.0
    job: dict | None = None
    finished_at: float | None = None
    lock: threading.Lock = field(default_factory=threading.Lock)
    pending_complete: list[int] = field(default_factory=list)


def cmd_run(args: argparse.Namespace) -> None:
    with open(args.timetable, encoding="utf-8") as f:
        tt = json.load(f)
    photos = tt["photos"][: args.limit] if args.limit else tt["photos"]
    bucket = tt["bucket"]
    if "dev" not in bucket or "dev" not in args.api:
        sys.exit("dev 전용이다 — 버킷·API 둘 다 dev 여야 한다")
    speed = args.speed

    secret = ssm("/wes/dev/jwt.secret", decrypt=True)
    api = Api(args.api, mint_jwt(secret, args.user_id, args.role, args.provider_id, ttl_seconds=4 * 3600))
    s3 = s3_client()

    span = max(p["upload_offset"] for p in photos) / speed
    log(f"재생 {args.label} · 갤러리 {args.count}개 · 간격 {args.stagger:.0f}초 · {speed}배속 · 갤러리당 {len(photos)}장 · "
        f"업로드 {span:.0f}초 · {args.api}")

    # 갤러리 만들기 (스튜디오 갤러리 — 사진 수 한도 없음)
    runs: list[GalleryRun] = []
    for k in range(args.count):
        _, body = api.post("/api/v1/galleries", {
            "workspaceId": args.workspace_id,
            "title": f"[부하 {args.label}] {k + 1}/{args.count}",
            "shootType": "REHEARSAL",
        })
        gid = body.get("id") or body.get("galleryId")
        if not gid:
            sys.exit(f"갤러리 id를 응답에서 찾지 못했다: {list(body)[:10]}")
        runs.append(GalleryRun(index=k, gallery_id=int(gid), t0=0.0, slots=[Slot(p) for p in photos]))
        log(f"갤러리 {k + 1}/{args.count} 만듦 → id {gid}")

    start = time.time() + args.start_delay
    for r in runs:
        r.t0 = start + r.index * args.stagger
    log(f"시작 {time.strftime('%H:%M:%S', time.localtime(start))} — 갤러리 ids: {','.join(str(r.gallery_id) for r in runs)}")

    copy_pool = ThreadPoolExecutor(max_workers=args.copy_workers)
    stop = threading.Event()

    def sleep_until(t: float) -> None:
        while not stop.is_set():
            d = t - time.time()
            if d <= 0:
                return
            time.sleep(min(d, 0.2))

    def issue_loop(r: GalleryRun) -> None:
        # 사진은 시각표에 issue_offset(created_at) 순서가 있다 — 그 순서로 100장씩 묶어, 묶음 첫 장의 시각에 URL을 받는다.
        order = sorted(r.slots, key=lambda s: s.photo["issue_offset"])
        for i in range(0, len(order), ISSUE_BATCH):
            batch = order[i:i + ISSUE_BATCH]
            due = r.t0 + batch[0].photo["issue_offset"] / speed
            sleep_until(due)
            r.issue_late_max = max(r.issue_late_max, time.time() - due)
            _, body = api.post(f"/api/v1/galleries/{r.gallery_id}/photos/upload-urls", {"files": [{
                "fileName": s.photo["file_name"], "contentType": s.photo["content_type"],
                "contentLength": s.photo["size"], "crc32c": s.photo["crc32c"]} for s in batch]})
            for s, up in zip(batch, body["uploads"]):
                s.photo_id, s.storage_key = up["photoId"], up["storageKey"]
                s.key_ready.set()

    def copy_one(r: GalleryRun, s: Slot) -> None:
        s.key_ready.wait()
        s.copy_late = time.time() - (r.t0 + s.photo["upload_offset"] / speed)
        try:
            s3.copy_object(CopySource={"Bucket": bucket, "Key": s.photo["seed_key"]}, Bucket=bucket,
                           Key=s.storage_key, ContentType=s.photo["content_type"],
                           MetadataDirective="REPLACE", ChecksumAlgorithm="CRC32C")
        except Exception as e:  # noqa: BLE001 — 한 장 실패가 재생 전체를 멈추지 않게
            with r.lock:
                r.copy_errors += 1
            log(f"복사 실패 갤러리 {r.gallery_id} 사진 {s.photo_id}: {e}")
            return
        with r.lock:
            r.pending_complete.append(s.photo_id)

    def upload_loop(r: GalleryRun) -> None:
        for s in sorted(r.slots, key=lambda s: s.photo["upload_offset"]):
            sleep_until(r.t0 + s.photo["upload_offset"] / speed)
            copy_pool.submit(copy_one, r, s)

    def complete_loop(r: GalleryRun) -> None:
        total = len(r.slots)
        while r.completed + r.copy_errors < total and not stop.is_set():
            time.sleep(COMPLETE_FLUSH_SECONDS)
            with r.lock:
                ids, r.pending_complete = r.pending_complete[:COMPLETE_MAX_BATCH], r.pending_complete[COMPLETE_MAX_BATCH:]
            if ids:
                api.post(f"/api/v1/galleries/{r.gallery_id}/photos/complete", {"photoIds": ids})
                r.completed += len(ids)
        r.finished_at = time.time()
        body = {"conceptCount": args.concept_count} if args.concept_count else None
        _, r.job = api.post(f"/api/v1/galleries/{r.gallery_id}/ai-analysis", body)
        log(f"갤러리 {r.gallery_id} 업로드 끝 {r.finished_at - r.t0:.0f}초 · 완료 {r.completed} · 복사 실패 {r.copy_errors} · "
            f"잡 {r.job.get('jobId')} 요청")

    threads = []
    for r in runs:
        for fn in (issue_loop, upload_loop, complete_loop):
            t = threading.Thread(target=fn, args=(r,), daemon=True, name=f"{fn.__name__}-{r.gallery_id}")
            t.start()
            threads.append(t)

    try:
        last = 0.0
        while any(t.is_alive() for t in threads):
            time.sleep(1)
            if time.time() - last >= 30:
                last = time.time()
                log("진행 " + " · ".join(f"{r.gallery_id}:{r.completed}/{len(r.slots)}" for r in runs))
    except KeyboardInterrupt:
        stop.set()
        log("중단 — 이미 나간 복사·완료 호출은 끝까지 간다. 남은 갤러리는 관리자 삭제로 멈춘다.")
    copy_pool.shutdown(wait=True)

    # 재생 정확도 — 복사가 시각표보다 얼마나 늦었나. 크면 노트북·S3 쪽이 병목이라 회차를 믿을 수 없다.
    print()
    print(f"재생 결과 {args.label}")
    print(f"{'갤러리':>8} {'사진':>6} {'완료':>6} {'실패':>4} {'업로드끝(초)':>12} {'복사지연 p95':>12} {'최대':>8} {'URL지연 최대':>12} {'잡':>6}")
    for r in runs:
        late = sorted(s.copy_late for s in r.slots)
        p95 = late[int(len(late) * 0.95) - 1] if late else 0.0
        print(f"{r.gallery_id:>8} {len(r.slots):>6} {r.completed:>6} {r.copy_errors:>4} "
              f"{(r.finished_at or time.time()) - r.t0:>12.0f} {p95:>12.2f} {late[-1] if late else 0:>8.2f} "
              f"{r.issue_late_max:>12.2f} {str((r.job or {}).get('jobId')):>6}")
    print(f"\ntimeline: scripts/load/timeline.sh dev --label {args.label} --ids {','.join(str(r.gallery_id) for r in runs)} "
          f"--limit {args.limit_minutes}min --events")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    st = sub.add_parser("studio", help="재생용 dev 스튜디오 만들기")
    st.add_argument("--user-id", type=int, required=True, help="OWNER가 될 dev 사용자")
    st.add_argument("--role", default="USER")
    st.add_argument("--provider-id", default="load-replay")
    st.add_argument("--name", default="부하 측정 스튜디오")
    st.add_argument("--gallery-url", default="load-test-studio", help="소문자·숫자·하이픈 3~50자, dev에서 안 쓰인 값")
    st.add_argument("--api", default=DEV_API)
    st.set_defaults(fn=cmd_studio)

    s = sub.add_parser("seed", help="운영 원본 → dev 씨앗 폴더 + 시각표")
    s.add_argument("--source", type=int, required=True, help="운영 원본 갤러리 id")
    s.add_argument("--csv", required=True, help="seed-dev.sh 가 운영 DB에서 뽑은 CSV")
    s.add_argument("--out", required=True, help="시각표 JSON 경로")
    s.set_defaults(fn=cmd_seed)

    r = sub.add_parser("run", help="시각표대로 dev 갤러리 N개 재생")
    r.add_argument("--timetable", required=True)
    r.add_argument("--label", required=True, help="회차 ID (B-2-1 등)")
    r.add_argument("--count", type=int, default=1)
    r.add_argument("--stagger", type=float, default=0.0, help="갤러리 사이 시작 간격(초)")
    r.add_argument("--speed", type=float, default=1.0, help="재생 배속 (2 = 회선 2배)")
    r.add_argument("--workspace-id", type=int, required=True, help="dev 스튜디오 워크스페이스")
    r.add_argument("--user-id", type=int, required=True, help="그 워크스페이스 멤버인 dev 사용자")
    r.add_argument("--role", default="USER")
    r.add_argument("--provider-id", default="load-replay")
    r.add_argument("--concept-count", type=int, default=None)
    r.add_argument("--api", default=DEV_API)
    r.add_argument("--start-delay", type=float, default=10.0, help="갤러리를 만든 뒤 시작까지(초)")
    r.add_argument("--copy-workers", type=int, default=96)
    r.add_argument("--limit", type=int, default=None, help="갤러리당 앞에서 N장만 (스모크용)")
    r.add_argument("--limit-minutes", type=int, default=10, help="출력하는 timeline 명령의 판정선")
    r.set_defaults(fn=cmd_run)

    args = p.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()

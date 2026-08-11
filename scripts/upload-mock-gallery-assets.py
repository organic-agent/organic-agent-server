#!/usr/bin/env python3
"""Upload or verify immutable Mock Gallery assets without ever overwriting them."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import math
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence


DEFAULT_REGION = "ap-northeast-2"
CACHE_CONTROL = "private,max-age=31536000,immutable"
SCHEMA_VERSION = 1
EMBEDDING_MODEL = "facebook/dinov2-base"
EMBEDDING_DIMENSION = 768
MAX_PHOTO_COUNT = 1_000
MAX_FILE_NAME_LENGTH = 255
MAX_STORAGE_KEY_LENGTH = 500
NORMALIZATION_TOLERANCE = 0.001
MAX_CONDITIONAL_PUT_ATTEMPTS = 3

TEMPLATE_VERSION_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,49}$")
SAFE_KEY_RE = re.compile(r"^[A-Za-z0-9/_.-]+$")
CONTENT_TYPE_RE = re.compile(r"^image/[a-z0-9][a-z0-9.+-]{0,93}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")

CommandRunner = Callable[..., subprocess.CompletedProcess[str]]


class UploadError(RuntimeError):
    """A local or remote invariant failed; no existing object was changed."""


@dataclass(frozen=True)
class ExpectedObject:
    key: str
    local_path: Path
    content_type: str
    sha256: str
    size: int
    template_version: str

    @property
    def checksum_sha256(self) -> str:
        return base64.b64encode(bytes.fromhex(self.sha256)).decode("ascii")

    @property
    def metadata(self) -> dict[str, str]:
        return {
            "sha256": self.sha256,
            "template-version": self.template_version,
        }


@dataclass(frozen=True)
class PreparedAssets:
    template_version: str
    objects: tuple[ExpectedObject, ...]

    @property
    def prefix(self) -> str:
        return f"mock-gallery/{self.template_version}/"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_prepared_assets(source_dir: Path, prepared_dir: Path) -> PreparedAssets:
    source_root = _existing_directory(source_dir, "source directory")
    prepared_root = _existing_directory(prepared_dir, "prepared directory")
    manifest_path = _safe_file(prepared_root, Path("manifest.json"), "manifest")

    try:
        with manifest_path.open("r", encoding="utf-8") as manifest_file:
            manifest = json.load(manifest_file, object_pairs_hook=_reject_duplicate_json_keys)
    except (OSError, UnicodeError, json.JSONDecodeError) as failure:
        raise UploadError(f"cannot read manifest {manifest_path}: {failure}") from failure

    root = _object(manifest, "manifest")
    _require_exact_int(root, "schemaVersion", SCHEMA_VERSION)
    template_version = _string(root, "templateVersion")
    if not TEMPLATE_VERSION_RE.fullmatch(template_version) or ".." in template_version:
        raise UploadError("manifest templateVersion is not safe")
    if _string(root, "embeddingModel") != EMBEDDING_MODEL:
        raise UploadError(f"manifest embeddingModel must be {EMBEDDING_MODEL}")
    _require_exact_int(root, "embeddingDimension", EMBEDDING_DIMENSION)

    photos = root.get("photos")
    if not isinstance(photos, list) or not 1 <= len(photos) <= MAX_PHOTO_COUNT:
        raise UploadError(f"manifest photos must contain 1..{MAX_PHOTO_COUNT} entries")

    original_prefix = f"mock-gallery/{template_version}/originals/"
    preview_prefix = f"mock-gallery/{template_version}/previews/"
    objects: list[ExpectedObject] = []
    keys: set[str] = set()
    display_orders: set[int] = set()

    for index, value in enumerate(photos):
        photo = _object(value, f"photos[{index}]")
        display_order = _int(photo, "displayOrder")
        if display_order in display_orders:
            raise UploadError(f"duplicate displayOrder: {display_order}")
        display_orders.add(display_order)

        storage_key = _validated_key(_string(photo, "storageKey"), original_prefix, "storageKey")
        preview_key = _validated_key(_string(photo, "previewKey"), preview_prefix, "previewKey")
        if not preview_key.endswith(".jpg"):
            raise UploadError(f"previewKey must end in .jpg: {preview_key}")
        for key in (storage_key, preview_key):
            if key in keys:
                raise UploadError(f"duplicate object key: {key}")
            keys.add(key)

        original_file_name = _string(photo, "originalFileName")
        if (
            not original_file_name
            or len(original_file_name) > MAX_FILE_NAME_LENGTH
            or any(
                ord(character) < 32 or 127 <= ord(character) <= 159
                for character in original_file_name
            )
        ):
            raise UploadError(f"unsafe originalFileName in photos[{index}]")
        original_relative = _safe_relative_path(original_file_name, f"photos[{index}].originalFileName")
        if len(original_relative.parts) != 1:
            raise UploadError(f"originalFileName must be a root-level file: {original_file_name}")

        preview_relative_key = preview_key[len(preview_prefix) :]
        preview_relative = Path("previews") / _safe_relative_path(
            preview_relative_key,
            f"photos[{index}].previewKey",
        )
        original_path = _safe_file(source_root, original_relative, f"original {original_file_name}")
        preview_path = _safe_file(prepared_root, preview_relative, f"preview {preview_key}")

        content_type = _string(photo, "contentType")
        if not CONTENT_TYPE_RE.fullmatch(content_type):
            raise UploadError(f"invalid contentType for {storage_key}: {content_type}")
        original_sha256 = _validated_sha256(photo, "originalSha256", storage_key)
        preview_sha256 = _validated_sha256(photo, "previewSha256", preview_key)
        _validate_embedding(photo, index)

        objects.append(
            _local_object(
                storage_key,
                original_path,
                content_type,
                original_sha256,
                template_version,
            )
        )
        objects.append(
            _local_object(
                preview_key,
                preview_path,
                "image/jpeg",
                preview_sha256,
                template_version,
            )
        )

    if display_orders != set(range(len(photos))):
        raise UploadError("displayOrder values must be contiguous from 0")

    return PreparedAssets(template_version=template_version, objects=tuple(objects))


def _reject_duplicate_json_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise UploadError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _existing_directory(path: Path, label: str) -> Path:
    try:
        resolved = path.expanduser().resolve(strict=True)
    except OSError as failure:
        raise UploadError(f"{label} does not exist: {path}") from failure
    if not resolved.is_dir():
        raise UploadError(f"{label} is not a directory: {path}")
    return resolved


def _safe_relative_path(value: str, label: str) -> Path:
    if (
        not value
        or "\\" in value
        or value.startswith("/")
        or any(part in ("", ".", "..") for part in value.split("/"))
    ):
        raise UploadError(f"unsafe relative path for {label}: {value!r}")
    relative = Path(value)
    if relative.is_absolute():
        raise UploadError(f"unsafe relative path for {label}: {value!r}")
    return relative


def _safe_file(root: Path, relative: Path, label: str) -> Path:
    try:
        candidate = (root / relative).resolve(strict=True)
    except OSError as failure:
        raise UploadError(f"{label} does not exist: {relative}") from failure
    try:
        candidate.relative_to(root)
    except ValueError as failure:
        raise UploadError(f"{label} escapes its root directory: {relative}") from failure
    if not candidate.is_file():
        raise UploadError(f"{label} is not a regular file: {relative}")
    return candidate


def _object(value: Any, label: str) -> Mapping[str, Any]:
    if not isinstance(value, dict):
        raise UploadError(f"{label} must be a JSON object")
    return value


def _string(value: Mapping[str, Any], field: str) -> str:
    result = value.get(field)
    if not isinstance(result, str):
        raise UploadError(f"{field} must be a string")
    return result


def _int(value: Mapping[str, Any], field: str) -> int:
    result = value.get(field)
    if isinstance(result, bool) or not isinstance(result, int):
        raise UploadError(f"{field} must be an integer")
    return result


def _require_exact_int(value: Mapping[str, Any], field: str, expected: int) -> None:
    actual = _int(value, field)
    if actual != expected:
        raise UploadError(f"{field} must be {expected}, got {actual}")


def _validated_key(key: str, prefix: str, label: str) -> str:
    relative_key = key[len(prefix) :] if key.startswith(prefix) else ""
    if (
        len(key) > MAX_STORAGE_KEY_LENGTH
        or not key.startswith(prefix)
        or len(key) <= len(prefix)
        or key != key.strip()
        or not SAFE_KEY_RE.fullmatch(key)
        or ".." in key
        or "//" in key
        or any(part in ("", ".", "..") for part in relative_key.split("/"))
    ):
        raise UploadError(f"unsafe {label}: {key!r}")
    return key


def _validated_sha256(photo: Mapping[str, Any], field: str, key: str) -> str:
    digest = _string(photo, field)
    if not SHA256_RE.fullmatch(digest):
        raise UploadError(f"invalid {field} for {key}")
    return digest


def _validate_embedding(photo: Mapping[str, Any], index: int) -> None:
    embedding = photo.get("embedding")
    if not isinstance(embedding, list) or len(embedding) != EMBEDDING_DIMENSION:
        raise UploadError(f"photos[{index}].embedding must have {EMBEDDING_DIMENSION} values")
    squared_norm = 0.0
    for value in embedding:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise UploadError(f"photos[{index}].embedding contains a non-finite number")
        try:
            numeric_value = float(value)
        except OverflowError as failure:
            raise UploadError(f"photos[{index}].embedding contains a non-finite number") from failure
        if not math.isfinite(numeric_value):
            raise UploadError(f"photos[{index}].embedding contains a non-finite number")
        squared_norm += numeric_value * numeric_value
    if abs(math.sqrt(squared_norm) - 1.0) > NORMALIZATION_TOLERANCE:
        raise UploadError(f"photos[{index}].embedding is not L2-normalized")


def _local_object(
    key: str,
    path: Path,
    content_type: str,
    expected_sha256: str,
    template_version: str,
) -> ExpectedObject:
    try:
        actual_sha256 = sha256_file(path)
        size = path.stat().st_size
    except OSError as failure:
        raise UploadError(f"cannot read local object for {key}: {path}") from failure
    if actual_sha256 != expected_sha256:
        raise UploadError(
            f"local SHA-256 mismatch for {key}: expected {expected_sha256}, got {actual_sha256}"
        )
    return ExpectedObject(
        key=key,
        local_path=path,
        content_type=content_type,
        sha256=expected_sha256,
        size=size,
        template_version=template_version,
    )


class AwsS3:
    def __init__(
        self,
        bucket: str,
        region: str,
        profile: str | None = None,
        runner: CommandRunner = subprocess.run,
    ) -> None:
        if not bucket.strip():
            raise UploadError("bucket must not be blank")
        if not region.strip():
            raise UploadError("region must not be blank")
        if profile is not None and not profile.strip():
            raise UploadError("profile must not be blank")
        self.bucket = bucket
        self.region = region
        self.profile = profile
        self.runner = runner

    def head(self, key: str) -> Mapping[str, Any] | None:
        result = self._run(
            "head-object",
            "--bucket",
            self.bucket,
            "--key",
            key,
            "--checksum-mode",
            "ENABLED",
        )
        if result.returncode != 0:
            if _is_not_found(result.stderr):
                return None
            self._raise_command_failure("head-object", key, result)
        return self._json_output("head-object", key, result.stdout)

    def put_if_absent(self, expected: ExpectedObject) -> bool:
        for attempt in range(MAX_CONDITIONAL_PUT_ATTEMPTS):
            result = self._run(
                "put-object",
                "--bucket",
                self.bucket,
                "--key",
                expected.key,
                "--body",
                str(expected.local_path),
                "--content-type",
                expected.content_type,
                "--cache-control",
                CACHE_CONTROL,
                "--metadata",
                json.dumps(expected.metadata, separators=(",", ":"), sort_keys=True),
                "--checksum-algorithm",
                "SHA256",
                "--checksum-sha256",
                expected.checksum_sha256,
                "--if-none-match",
                "*",
            )
            if result.returncode == 0:
                return True
            if _is_precondition_failed(result.stderr):
                return False
            if _is_conditional_conflict(result.stderr) and attempt + 1 < MAX_CONDITIONAL_PUT_ATTEMPTS:
                continue
            self._raise_command_failure("put-object", expected.key, result)
        raise AssertionError("unreachable")

    def list_keys(self, prefix: str) -> set[str]:
        keys: set[str] = set()
        continuation_token: str | None = None
        seen_tokens: set[str] = set()
        while True:
            arguments = [
                "--bucket",
                self.bucket,
                "--prefix",
                prefix,
                "--max-keys",
                "1000",
            ]
            if continuation_token is not None:
                arguments.extend(("--continuation-token", continuation_token))
            result = self._run("list-objects-v2", *arguments, no_paginate=True)
            if result.returncode != 0:
                self._raise_command_failure("list-objects-v2", prefix, result)
            response = self._json_output("list-objects-v2", prefix, result.stdout)
            contents = response.get("Contents", [])
            if not isinstance(contents, list):
                raise UploadError("list-objects-v2 returned invalid Contents")
            for item in contents:
                if not isinstance(item, dict) or not isinstance(item.get("Key"), str):
                    raise UploadError("list-objects-v2 returned an invalid object key")
                keys.add(item["Key"])

            if not response.get("IsTruncated", False):
                return keys
            next_token = response.get("NextContinuationToken")
            if not isinstance(next_token, str) or not next_token or next_token in seen_tokens:
                raise UploadError("list-objects-v2 returned an invalid continuation token")
            seen_tokens.add(next_token)
            continuation_token = next_token

    def _run(
        self,
        operation: str,
        *arguments: str,
        no_paginate: bool = False,
    ) -> subprocess.CompletedProcess[str]:
        command = ["aws", "s3api", operation, *arguments, "--region", self.region]
        if self.profile is not None:
            command.extend(("--profile", self.profile))
        command.extend(("--output", "json", "--no-cli-pager"))
        if no_paginate:
            command.append("--no-paginate")
        try:
            return self.runner(command, check=False, capture_output=True, text=True)
        except FileNotFoundError as failure:
            raise UploadError("AWS CLI executable 'aws' was not found") from failure
        except OSError as failure:
            raise UploadError(f"could not execute AWS CLI: {failure}") from failure

    @staticmethod
    def _json_output(operation: str, key: str, output: str) -> Mapping[str, Any]:
        try:
            value = json.loads(output or "{}")
        except json.JSONDecodeError as failure:
            raise UploadError(f"{operation} returned invalid JSON for {key}") from failure
        if not isinstance(value, dict):
            raise UploadError(f"{operation} returned non-object JSON for {key}")
        return value

    @staticmethod
    def _raise_command_failure(
        operation: str,
        key: str,
        result: subprocess.CompletedProcess[str],
    ) -> None:
        detail = result.stderr.strip() or f"exit status {result.returncode}"
        raise UploadError(f"AWS {operation} failed for {key}: {detail}")


def _is_not_found(stderr: str) -> bool:
    normalized = stderr.lower()
    return "when calling the headobject operation" in normalized and any(
        f"an error occurred ({code})" in normalized
        for code in ("404", "nosuchkey", "notfound")
    )


def _is_precondition_failed(stderr: str) -> bool:
    normalized = stderr.lower()
    return "when calling the putobject operation" in normalized and any(
        f"an error occurred ({code})" in normalized
        for code in ("412", "preconditionfailed")
    )


def _is_conditional_conflict(stderr: str) -> bool:
    normalized = stderr.lower()
    return "when calling the putobject operation" in normalized and any(
        f"an error occurred ({code})" in normalized
        for code in ("409", "conditionalrequestconflict")
    )


def validate_remote_object(expected: ExpectedObject, response: Mapping[str, Any]) -> None:
    mismatches: list[str] = []
    if response.get("ContentLength") != expected.size:
        mismatches.append(f"size expected={expected.size} actual={response.get('ContentLength')!r}")
    if response.get("ContentType") != expected.content_type:
        mismatches.append(
            f"content-type expected={expected.content_type!r} actual={response.get('ContentType')!r}"
        )
    if response.get("CacheControl") != CACHE_CONTROL:
        mismatches.append(
            f"cache-control expected={CACHE_CONTROL!r} actual={response.get('CacheControl')!r}"
        )

    metadata = response.get("Metadata")
    normalized_metadata: dict[str, str] | None = None
    if isinstance(metadata, dict) and all(isinstance(key, str) and isinstance(value, str) for key, value in metadata.items()):
        normalized_metadata = {key.lower(): value for key, value in metadata.items()}
    if normalized_metadata != expected.metadata:
        mismatches.append(f"metadata expected={expected.metadata!r} actual={metadata!r}")

    if response.get("ChecksumSHA256") != expected.checksum_sha256:
        mismatches.append(
            "checksum expected="
            f"{expected.checksum_sha256!r} actual={response.get('ChecksumSHA256')!r}"
        )
    if mismatches:
        raise UploadError(f"existing object does not match {expected.key}: " + "; ".join(mismatches))


def upload_or_verify(
    prepared: PreparedAssets,
    s3: AwsS3,
    verify_only: bool,
    output: Callable[[str], None] = print,
) -> None:
    for expected in prepared.objects:
        response = s3.head(expected.key)
        if response is None:
            if verify_only:
                raise UploadError(f"required object is missing: {expected.key}")
            uploaded = s3.put_if_absent(expected)
            response = s3.head(expected.key)
            if response is None:
                raise UploadError(f"object is still missing after conditional upload: {expected.key}")
            validate_remote_object(expected, response)
            output(("uploaded" if uploaded else "verified concurrent upload") + f": {expected.key}")
        else:
            validate_remote_object(expected, response)
            output(f"verified existing: {expected.key}")

    expected_keys = {expected.key for expected in prepared.objects}
    actual_keys = s3.list_keys(prepared.prefix)
    if actual_keys != expected_keys:
        missing = sorted(expected_keys - actual_keys)
        unexpected = sorted(actual_keys - expected_keys)
        details: list[str] = []
        if missing:
            details.append("missing=" + ", ".join(missing))
        if unexpected:
            details.append("unexpected=" + ", ".join(unexpected))
        raise UploadError(f"S3 prefix inventory mismatch for {prepared.prefix}: " + "; ".join(details))
    output(f"verified exact prefix inventory: {prepared.prefix} ({len(actual_keys)} objects)")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Conditionally upload and strictly verify immutable Mock Gallery S3 assets.",
    )
    parser.add_argument("--source-dir", required=True, type=Path)
    parser.add_argument("--prepared-dir", required=True, type=Path)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--region", default=DEFAULT_REGION)
    parser.add_argument("--profile")
    parser.add_argument(
        "--verify-only",
        action="store_true",
        help="fail on missing objects instead of uploading them",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    arguments = parser.parse_args(argv)
    try:
        prepared = load_prepared_assets(arguments.source_dir, arguments.prepared_dir)
        s3 = AwsS3(
            bucket=arguments.bucket,
            region=arguments.region,
            profile=arguments.profile,
        )
        upload_or_verify(prepared, s3, arguments.verify_only)
    except UploadError as failure:
        print(f"error: {failure}", file=sys.stderr)
        return 1

    action = "verified" if arguments.verify_only else "uploaded/verified"
    print(f"{action} {len(prepared.objects)} immutable objects for {prepared.template_version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

from __future__ import annotations

import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPT_PATH = REPOSITORY_ROOT / "scripts" / "upload-mock-gallery-assets.py"
SPEC = importlib.util.spec_from_file_location("mock_gallery_upload", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot load {SCRIPT_PATH}")
upload = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = upload
SPEC.loader.exec_module(upload)


def _sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _manifest(original: bytes, preview: bytes) -> dict:
    return {
        "schemaVersion": 1,
        "templateVersion": "sample-v1",
        "embeddingModel": "facebook/dinov2-base",
        "embeddingDimension": 768,
        "photos": [
            {
                "storageKey": "mock-gallery/sample-v1/originals/couple-one.png",
                "previewKey": "mock-gallery/sample-v1/previews/couple-one.jpg",
                "originalFileName": "couple-one.png",
                "contentType": "image/png",
                "displayOrder": 0,
                "originalSha256": _sha256(original),
                "previewSha256": _sha256(preview),
                "embedding": [1.0] + [0.0] * 767,
            }
        ],
    }


def _head(expected) -> dict:
    return {
        "ContentLength": expected.size,
        "ContentType": expected.content_type,
        "CacheControl": upload.CACHE_CONTROL,
        "Metadata": expected.metadata,
        "ChecksumSHA256": expected.checksum_sha256,
    }


class _Runner:
    def __init__(self, responses: list[subprocess.CompletedProcess[str]]) -> None:
        self.responses = responses
        self.commands: list[list[str]] = []

    def __call__(self, command: list[str], **kwargs) -> subprocess.CompletedProcess[str]:
        self.commands.append(command)
        if not self.responses:
            raise AssertionError(f"unexpected command: {command}")
        return self.responses.pop(0)


def _completed(returncode: int = 0, body: dict | None = None, stderr: str = ""):
    return subprocess.CompletedProcess(
        args=[],
        returncode=returncode,
        stdout=json.dumps(body or {}),
        stderr=stderr,
    )


class LocalManifestValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source_dir = self.root / "source"
        self.prepared_dir = self.root / "prepared"
        self.source_dir.mkdir()
        (self.prepared_dir / "previews").mkdir(parents=True)
        self.original = b"original image bytes"
        self.preview = b"preview jpeg bytes"
        (self.source_dir / "couple-one.png").write_bytes(self.original)
        (self.prepared_dir / "previews" / "couple-one.jpg").write_bytes(self.preview)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_manifest(self, manifest: dict) -> None:
        (self.prepared_dir / "manifest.json").write_text(
            json.dumps(manifest),
            encoding="utf-8",
        )

    def test_loads_originals_and_previews_and_checks_exact_hashes(self) -> None:
        self.write_manifest(_manifest(self.original, self.preview))

        prepared = upload.load_prepared_assets(self.source_dir, self.prepared_dir)

        self.assertEqual("sample-v1", prepared.template_version)
        self.assertEqual(
            [
                "mock-gallery/sample-v1/originals/couple-one.png",
                "mock-gallery/sample-v1/previews/couple-one.jpg",
            ],
            [item.key for item in prepared.objects],
        )
        self.assertEqual(["image/png", "image/jpeg"], [item.content_type for item in prepared.objects])

    def test_hash_mismatch_fails_before_any_aws_work(self) -> None:
        manifest = _manifest(self.original, self.preview)
        manifest["photos"][0]["previewSha256"] = "0" * 64
        self.write_manifest(manifest)

        with self.assertRaisesRegex(upload.UploadError, "local SHA-256 mismatch"):
            upload.load_prepared_assets(self.source_dir, self.prepared_dir)

    def test_rejects_traversal_in_local_name_and_s3_key(self) -> None:
        manifest = _manifest(self.original, self.preview)
        manifest["photos"][0]["originalFileName"] = "../couple-one.png"
        manifest["photos"][0]["storageKey"] = (
            "mock-gallery/sample-v1/originals/../couple-one.png"
        )
        self.write_manifest(manifest)

        with self.assertRaisesRegex(upload.UploadError, "unsafe storageKey"):
            upload.load_prepared_assets(self.source_dir, self.prepared_dir)

    def test_rejects_non_canonical_dot_segments(self) -> None:
        cases = [
            ("./couple-one.png", "mock-gallery/sample-v1/originals/couple-one.png"),
            ("couple-one.png", "mock-gallery/sample-v1/originals/./couple-one.png"),
        ]
        for original_file_name, storage_key in cases:
            with self.subTest(original_file_name=original_file_name, storage_key=storage_key):
                manifest = _manifest(self.original, self.preview)
                manifest["photos"][0]["originalFileName"] = original_file_name
                manifest["photos"][0]["storageKey"] = storage_key
                self.write_manifest(manifest)

                with self.assertRaises(upload.UploadError):
                    upload.load_prepared_assets(self.source_dir, self.prepared_dir)


class RemoteObjectSafetyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        local_path = Path(self.temporary.name) / "asset.jpg"
        local_path.write_bytes(b"asset")
        digest = _sha256(b"asset")
        self.expected = upload.ExpectedObject(
            key="mock-gallery/sample-v1/previews/asset.jpg",
            local_path=local_path,
            content_type="image/jpeg",
            sha256=digest,
            size=5,
            template_version="sample-v1",
        )
        self.prepared = upload.PreparedAssets("sample-v1", (self.expected,))

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_existing_exact_object_is_never_put(self) -> None:
        runner = _Runner(
            [
                _completed(body=_head(self.expected)),
                _completed(body={"Contents": [{"Key": self.expected.key}]}),
            ]
        )
        s3 = upload.AwsS3("bucket", "ap-northeast-2", runner=runner)

        upload.upload_or_verify(self.prepared, s3, verify_only=False, output=lambda message: None)

        self.assertEqual(["head-object", "list-objects-v2"], [command[2] for command in runner.commands])

    def test_existing_mismatch_fails_without_put(self) -> None:
        wrong = _head(self.expected)
        wrong["CacheControl"] = "public,max-age=60"
        runner = _Runner([_completed(body=wrong)])
        s3 = upload.AwsS3("bucket", "ap-northeast-2", runner=runner)

        with self.assertRaisesRegex(upload.UploadError, "existing object does not match"):
            upload.upload_or_verify(self.prepared, s3, verify_only=False, output=lambda message: None)

        self.assertEqual(["head-object"], [command[2] for command in runner.commands])

    def test_verify_only_never_uploads_a_missing_object(self) -> None:
        runner = _Runner(
            [
                _completed(
                    returncode=254,
                    stderr=(
                        "An error occurred (404) when calling the HeadObject operation: Not Found"
                    ),
                )
            ]
        )
        s3 = upload.AwsS3("bucket", "ap-northeast-2", runner=runner)

        with self.assertRaisesRegex(upload.UploadError, "required object is missing"):
            upload.upload_or_verify(self.prepared, s3, verify_only=True, output=lambda message: None)

        self.assertEqual(["head-object"], [command[2] for command in runner.commands])

    def test_missing_object_uses_conditional_immutable_put_then_rechecks_head(self) -> None:
        runner = _Runner(
            [
                _completed(
                    returncode=254,
                    stderr=(
                        "An error occurred (404) when calling the HeadObject operation: Not Found"
                    ),
                ),
                _completed(body={"ChecksumSHA256": self.expected.checksum_sha256}),
                _completed(body=_head(self.expected)),
                _completed(body={"Contents": [{"Key": self.expected.key}]}),
            ]
        )
        s3 = upload.AwsS3("bucket", "ap-northeast-2", profile="prod", runner=runner)

        upload.upload_or_verify(self.prepared, s3, verify_only=False, output=lambda message: None)

        put = runner.commands[1]
        self.assertEqual("put-object", put[2])
        self.assertEqual("*", put[put.index("--if-none-match") + 1])
        self.assertEqual("SHA256", put[put.index("--checksum-algorithm") + 1])
        self.assertEqual(
            self.expected.checksum_sha256,
            put[put.index("--checksum-sha256") + 1],
        )
        self.assertEqual(upload.CACHE_CONTROL, put[put.index("--cache-control") + 1])
        self.assertEqual(self.expected.content_type, put[put.index("--content-type") + 1])
        self.assertEqual(self.expected.metadata, json.loads(put[put.index("--metadata") + 1]))
        self.assertEqual("prod", put[put.index("--profile") + 1])

    def test_precondition_race_is_verified_instead_of_overwritten(self) -> None:
        runner = _Runner(
            [
                _completed(
                    returncode=254,
                    stderr=(
                        "An error occurred (404) when calling the HeadObject operation: Not Found"
                    ),
                ),
                _completed(
                    returncode=254,
                    stderr=(
                        "An error occurred (PreconditionFailed) when calling the PutObject operation: "
                        "At least one of the pre-conditions you specified did not hold"
                    ),
                ),
                _completed(body=_head(self.expected)),
                _completed(body={"Contents": [{"Key": self.expected.key}]}),
            ]
        )
        s3 = upload.AwsS3("bucket", "ap-northeast-2", runner=runner)

        upload.upload_or_verify(self.prepared, s3, verify_only=False, output=lambda message: None)

        self.assertEqual(
            ["head-object", "put-object", "head-object", "list-objects-v2"],
            [command[2] for command in runner.commands],
        )

    def test_conditional_request_conflict_retries_only_conditional_put(self) -> None:
        runner = _Runner(
            [
                _completed(
                    returncode=254,
                    stderr=(
                        "An error occurred (404) when calling the HeadObject operation: Not Found"
                    ),
                ),
                _completed(
                    returncode=254,
                    stderr=(
                        "An error occurred (ConditionalRequestConflict) when calling the PutObject "
                        "operation: A conflicting conditional operation is currently in progress"
                    ),
                ),
                _completed(body={"ChecksumSHA256": self.expected.checksum_sha256}),
                _completed(body=_head(self.expected)),
                _completed(body={"Contents": [{"Key": self.expected.key}]}),
            ]
        )
        s3 = upload.AwsS3("bucket", "ap-northeast-2", runner=runner)

        upload.upload_or_verify(self.prepared, s3, verify_only=False, output=lambda message: None)

        put_commands = [command for command in runner.commands if command[2] == "put-object"]
        self.assertEqual(2, len(put_commands))
        self.assertTrue(all("--if-none-match" in command for command in put_commands))

    def test_non_404_head_failure_is_not_treated_as_absent(self) -> None:
        runner = _Runner(
            [_completed(returncode=254, stderr="An error occurred (AccessDenied): Access Denied")]
        )
        s3 = upload.AwsS3("bucket", "ap-northeast-2", runner=runner)

        with self.assertRaisesRegex(upload.UploadError, "AWS head-object failed"):
            upload.upload_or_verify(self.prepared, s3, verify_only=False, output=lambda message: None)

        self.assertEqual(["head-object"], [command[2] for command in runner.commands])

    def test_exact_prefix_inventory_rejects_unexpected_objects(self) -> None:
        runner = _Runner(
            [
                _completed(body=_head(self.expected)),
                _completed(
                    body={
                        "Contents": [
                            {"Key": self.expected.key},
                            {"Key": "mock-gallery/sample-v1/previews/stale.jpg"},
                        ]
                    }
                ),
            ]
        )
        s3 = upload.AwsS3("bucket", "ap-northeast-2", runner=runner)

        with self.assertRaisesRegex(upload.UploadError, "unexpected=.*stale.jpg"):
            upload.upload_or_verify(self.prepared, s3, verify_only=False, output=lambda message: None)


if __name__ == "__main__":
    unittest.main()

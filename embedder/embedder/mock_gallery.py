"""Prepare versioned Mock Gallery assets without touching S3.

The source directory remains the source of truth for originals.  This module reads its
``manifest.csv``, creates the same JPEG derivatives as the embedding Lambda, embeds the
prepared images in batches, and publishes only ``manifest.json`` plus ``previews/`` to a
new output directory.

Optional image/ML dependencies are deliberately imported inside :func:`prepare_dataset`.
Catalog and manifest validation can therefore run in the server CI image (and under
``python -S``) without Pillow, NumPy, Torch, or Transformers installed.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import logging
import math
import os
import re
import shutil
import stat
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Sequence


log = logging.getLogger(__name__)

SOURCE_MANIFEST_NAME = "manifest.csv"
OUTPUT_MANIFEST_NAME = "manifest.json"
PREVIEWS_DIRECTORY_NAME = "previews"
SCHEMA_VERSION = 1

# Keep these values in lockstep with Settings.from_env().  They are repeated here rather
# than importing embedder.config so this module's validation surface stays stdlib-only.
DEFAULT_MODEL_ID = "facebook/dinov2-base"
DEFAULT_EMBEDDING_DIMENSION = 768
DEFAULT_RESIZE_LONG_EDGE = 1024
DEFAULT_PREVIEW_QUALITY = 82
DEFAULT_BATCH_SIZE = 8

MAX_PHOTO_COUNT = 1_000
MAX_FILE_NAME_LENGTH = 255
NORMALIZATION_TOLERANCE = 0.001

CONTENT_TYPE_BY_SUFFIX = {
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".webp": "image/webp",
    ".heic": "image/heic",
    ".heif": "image/heif",
}

_TEMPLATE_VERSION = re.compile(r"^[a-z0-9][a-z0-9._-]{0,49}$")
_SAFE_FILE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,254}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")


class MockGalleryPreparationError(RuntimeError):
    """The input or generated dataset cannot be safely published."""


class CatalogError(MockGalleryPreparationError):
    """``manifest.csv`` and the source image files do not form an exact catalog."""


@dataclass(frozen=True)
class CatalogPhoto:
    filename: str
    path: Path
    content_type: str
    preview_filename: str
    display_order: int


@dataclass(frozen=True)
class PreparedPhoto:
    source: CatalogPhoto
    original_sha256: str
    preview_sha256: str
    embedding: tuple[float, ...]


@dataclass(frozen=True)
class PreparationResult:
    output_dir: Path
    manifest_path: Path
    preview_count: int


def validate_template_version(template_version: str) -> str:
    """Return a server-compatible template version or raise ``ValueError``."""

    if not _TEMPLATE_VERSION.fullmatch(template_version) or ".." in template_version:
        raise ValueError(
            "template version must match ^[a-z0-9][a-z0-9._-]{0,49}$ "
            "and must not contain '..'"
        )
    return template_version


def preview_filename_for(filename: str) -> str:
    """Return the collision-checked JPEG filename used in ``previews/``."""

    _validate_safe_image_filename(filename)
    return f"{Path(filename).stem}.jpg"


def original_storage_key(template_version: str, filename: str) -> str:
    validate_template_version(template_version)
    _validate_safe_image_filename(filename)
    return f"mock-gallery/{template_version}/originals/{filename}"


def preview_storage_key(template_version: str, preview_filename: str) -> str:
    validate_template_version(template_version)
    if not _SAFE_FILE_NAME.fullmatch(preview_filename) or not preview_filename.endswith(
        ".jpg"
    ):
        raise ValueError(f"unsafe preview filename: {preview_filename!r}")
    if ".." in preview_filename:
        raise ValueError(f"unsafe preview filename: {preview_filename!r}")
    return f"mock-gallery/{template_version}/previews/{preview_filename}"


def read_catalog(source_dir: str | os.PathLike[str]) -> list[CatalogPhoto]:
    """Read and validate the exact root-level source-image catalog.

    Non-image companion files (for example the dataset README) are ignored.  Every
    supported root-level image must occur exactly once in ``manifest.csv``, and every
    manifest filename must resolve to a regular, non-symlink image in that directory.
    Row order is preserved as ``display_order``.
    """

    source = Path(source_dir)
    try:
        source = source.resolve(strict=True)
    except OSError as failure:
        raise CatalogError(f"source directory does not exist: {source}") from failure
    if not source.is_dir():
        raise CatalogError(f"source path is not a directory: {source}")

    manifest_path = source / SOURCE_MANIFEST_NAME
    _require_regular_file(manifest_path, description="source manifest")

    try:
        with manifest_path.open("r", encoding="utf-8-sig", newline="") as stream:
            reader = csv.reader(stream, strict=True)
            try:
                header = next(reader)
            except StopIteration as failure:
                raise CatalogError(f"{SOURCE_MANIFEST_NAME} is empty") from failure

            if not header or any(not name for name in header):
                raise CatalogError("manifest header contains an empty column name")
            if len(set(header)) != len(header):
                raise CatalogError("manifest header contains duplicate column names")
            if "filename" not in header:
                raise CatalogError("manifest must contain a filename column")

            filename_index = header.index("filename")
            filenames: list[str] = []
            for line_number, row in enumerate(reader, start=2):
                if len(row) != len(header):
                    raise CatalogError(
                        f"manifest row {line_number} has {len(row)} columns; "
                        f"expected {len(header)}"
                    )
                filename = row[filename_index]
                try:
                    _validate_safe_image_filename(filename)
                except ValueError as failure:
                    raise CatalogError(
                        f"manifest row {line_number} has an invalid filename: {failure}"
                    ) from failure
                filenames.append(filename)
    except (OSError, UnicodeError, csv.Error) as failure:
        raise CatalogError(f"cannot read {manifest_path}: {failure}") from failure

    if not 1 <= len(filenames) <= MAX_PHOTO_COUNT:
        raise CatalogError(
            f"manifest must contain 1..{MAX_PHOTO_COUNT} image rows; got {len(filenames)}"
        )

    _require_unique_names(filenames, label="manifest filename")
    preview_filenames = [preview_filename_for(filename) for filename in filenames]
    _require_unique_names(preview_filenames, label="preview filename")

    actual_images = _scan_source_images(source)
    expected_names = set(filenames)
    actual_names = set(actual_images)
    if expected_names != actual_names:
        missing = sorted(expected_names - actual_names)
        extra = sorted(actual_names - expected_names)
        details: list[str] = []
        if missing:
            details.append(f"missing={missing}")
        if extra:
            details.append(f"unlisted={extra}")
        raise CatalogError("manifest/image set mismatch: " + ", ".join(details))

    return [
        CatalogPhoto(
            filename=filename,
            path=actual_images[filename],
            content_type=CONTENT_TYPE_BY_SUFFIX[Path(filename).suffix.lower()],
            preview_filename=preview_filenames[display_order],
            display_order=display_order,
        )
        for display_order, filename in enumerate(filenames)
    ]


def build_manifest(
    *,
    template_version: str,
    embedding_model: str,
    embedding_dimension: int,
    photos: Sequence[PreparedPhoto],
) -> dict[str, Any]:
    """Build and fully validate the schema-version-1 server manifest."""

    validate_template_version(template_version)
    if not embedding_model or embedding_model != embedding_model.strip():
        raise ValueError("embedding model must be a non-empty trimmed string")
    if embedding_dimension <= 0:
        raise ValueError("embedding dimension must be positive")
    if not 1 <= len(photos) <= MAX_PHOTO_COUNT:
        raise ValueError(f"photos must contain 1..{MAX_PHOTO_COUNT} entries")

    display_orders = {photo.source.display_order for photo in photos}
    if display_orders != set(range(len(photos))):
        raise ValueError("display orders must be contiguous from zero")

    ordered_photos = sorted(photos, key=lambda photo: photo.source.display_order)
    manifest_photos: list[dict[str, Any]] = []
    storage_keys: set[str] = set()
    preview_keys: set[str] = set()
    for photo in ordered_photos:
        source = photo.source
        _validate_safe_image_filename(source.filename)
        expected_content_type = CONTENT_TYPE_BY_SUFFIX[Path(source.filename).suffix.lower()]
        if source.content_type != expected_content_type:
            raise ValueError(
                f"content type for {source.filename!r} must be {expected_content_type!r}"
            )
        expected_preview_filename = preview_filename_for(source.filename)
        if source.preview_filename != expected_preview_filename:
            raise ValueError(
                f"preview filename for {source.filename!r} must be "
                f"{expected_preview_filename!r}"
            )
        _validate_sha256(photo.original_sha256, label="original SHA-256")
        _validate_sha256(photo.preview_sha256, label="preview SHA-256")

        vector = [float(value) for value in photo.embedding]
        if len(vector) != embedding_dimension:
            raise ValueError(
                f"embedding for {source.filename!r} has {len(vector)} values; "
                f"expected {embedding_dimension}"
            )
        if not all(math.isfinite(value) for value in vector):
            raise ValueError(f"embedding for {source.filename!r} contains non-finite values")
        norm = math.sqrt(math.fsum(value * value for value in vector))
        if abs(norm - 1.0) > NORMALIZATION_TOLERANCE:
            raise ValueError(
                f"embedding for {source.filename!r} is not L2-normalized (norm={norm})"
            )

        storage_key = original_storage_key(template_version, source.filename)
        preview_key = preview_storage_key(template_version, source.preview_filename)
        if storage_key in storage_keys:
            raise ValueError(f"duplicate storage key: {storage_key}")
        if preview_key in preview_keys:
            raise ValueError(f"duplicate preview key: {preview_key}")
        storage_keys.add(storage_key)
        preview_keys.add(preview_key)

        manifest_photos.append(
            {
                "storageKey": storage_key,
                "previewKey": preview_key,
                "originalFileName": source.filename,
                "contentType": source.content_type,
                "displayOrder": source.display_order,
                "originalSha256": photo.original_sha256,
                "previewSha256": photo.preview_sha256,
                "embedding": vector,
            }
        )

    return {
        "schemaVersion": SCHEMA_VERSION,
        "templateVersion": template_version,
        "embeddingModel": embedding_model,
        "embeddingDimension": embedding_dimension,
        "photos": manifest_photos,
    }


def serialize_manifest(manifest: dict[str, Any]) -> bytes:
    """Serialize deterministically and reject JSON's non-standard NaN/Infinity values."""

    return (
        json.dumps(
            manifest,
            ensure_ascii=False,
            indent=2,
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")


def prepare_dataset(
    *,
    source_dir: str | os.PathLike[str],
    output_dir: str | os.PathLike[str],
    template_version: str,
    model_id: str = DEFAULT_MODEL_ID,
    embedding_dimension: int = DEFAULT_EMBEDDING_DIMENSION,
    resize_long_edge: int = DEFAULT_RESIZE_LONG_EDGE,
    preview_quality: int = DEFAULT_PREVIEW_QUALITY,
    batch_size: int = DEFAULT_BATCH_SIZE,
) -> PreparationResult:
    """Prepare and atomically publish a Mock Gallery dataset locally.

    The final directory must not exist.  Work is done in a sibling staging directory and
    renamed only after every preview, embedding, hash, and manifest has been validated.
    Originals are read in place and are never copied into the output.
    """

    validate_template_version(template_version)
    if not model_id or model_id != model_id.strip():
        raise ValueError("model id must be a non-empty trimmed string")
    if embedding_dimension <= 0:
        raise ValueError("embedding dimension must be positive")
    if resize_long_edge <= 0:
        raise ValueError("resize long edge must be positive")
    if not 1 <= preview_quality <= 95:
        raise ValueError("preview quality must be in the range 1..95")
    if batch_size <= 0:
        raise ValueError("batch size must be positive")

    catalog = read_catalog(source_dir)
    source = catalog[0].path.parent.resolve(strict=True)
    requested_output = Path(output_dir).expanduser()
    # Check before resolving so a broken final-component symlink is not silently followed
    # and turned into a directory at its target.
    if _lexists(requested_output):
        raise FileExistsError(f"output directory already exists: {requested_output}")
    output_parent = requested_output.parent.resolve(strict=False)
    output = output_parent / requested_output.name
    if output == source or source in output.parents:
        raise ValueError("output directory must not be inside the source directory")
    if _lexists(output):
        raise FileExistsError(f"output directory already exists: {output}")

    output_parent.mkdir(parents=True, exist_ok=True)
    if not output_parent.is_dir():
        raise NotADirectoryError(f"output parent is not a directory: {output_parent}")

    # These imports are intentionally below all pure validation.  Importing this module
    # must remain possible in the JVM/server CI image, which does not install ML packages.
    try:
        import numpy as np

        from embedder import images
        from embedder.model import DinoEmbedder
    except ImportError as failure:
        raise MockGalleryPreparationError(
            "image/ML dependencies are unavailable; install embedder/requirements.txt"
        ) from failure

    try:
        embedder = DinoEmbedder(model_id, embedding_dimension)
    except Exception as failure:
        raise MockGalleryPreparationError(
            f"cannot load embedding model {model_id!r}: {failure}"
        ) from failure
    staging = Path(
        tempfile.mkdtemp(prefix=f".{output.name}.tmp-", dir=str(output.parent))
    )
    try:
        previews_dir = staging / PREVIEWS_DIRECTORY_NAME
        previews_dir.mkdir()
        prepared_photos: list[PreparedPhoto] = []

        for batch_start in range(0, len(catalog), batch_size):
            batch = catalog[batch_start : batch_start + batch_size]
            prepared_images: list[Any] = []
            batch_metadata: list[tuple[CatalogPhoto, str, str]] = []
            active_filename = batch[0].filename
            try:
                for source_photo in batch:
                    active_filename = source_photo.filename
                    original_bytes = source_photo.path.read_bytes()
                    original_sha256 = hashlib.sha256(original_bytes).hexdigest()
                    original_image = images.open_original(original_bytes)
                    try:
                        prepared_image = images.prepare(
                            original_image, resize_long_edge
                        )
                    finally:
                        original_image.close()

                    try:
                        preview_bytes = images.to_jpeg(
                            prepared_image, preview_quality
                        )
                        preview_sha256 = hashlib.sha256(preview_bytes).hexdigest()
                        _write_new_file(
                            previews_dir / source_photo.preview_filename,
                            preview_bytes,
                        )
                    except Exception:
                        prepared_image.close()
                        raise

                    prepared_images.append(prepared_image)
                    batch_metadata.append(
                        (source_photo, original_sha256, preview_sha256)
                    )

                vectors = np.asarray(embedder.encode(prepared_images))
                expected_shape = (len(batch), embedding_dimension)
                if vectors.shape != expected_shape:
                    raise MockGalleryPreparationError(
                        f"model returned shape {vectors.shape}; expected {expected_shape}"
                    )
                if not bool(np.isfinite(vectors).all()):
                    raise MockGalleryPreparationError(
                        "model returned a non-finite embedding"
                    )
                norms = np.linalg.norm(vectors, axis=1)
                if not bool(
                    np.allclose(
                        norms,
                        1.0,
                        rtol=0.0,
                        atol=NORMALIZATION_TOLERANCE,
                    )
                ):
                    raise MockGalleryPreparationError(
                        f"model returned non-normalized embeddings: {norms.tolist()}"
                    )

                # Shape validation above already proves both sides have equal length.
                # Avoid zip(strict=True) so the preparer also runs with macOS' Python 3.9.
                for metadata, vector in zip(batch_metadata, vectors):
                    source_photo, original_sha256, preview_sha256 = metadata
                    prepared_photos.append(
                        PreparedPhoto(
                            source=source_photo,
                            original_sha256=original_sha256,
                            preview_sha256=preview_sha256,
                            embedding=tuple(float(value) for value in vector.tolist()),
                        )
                    )
            except Exception as failure:
                if isinstance(failure, MockGalleryPreparationError):
                    raise
                raise MockGalleryPreparationError(
                    f"failed to prepare {active_filename!r}: {failure}"
                ) from failure
            finally:
                for prepared_image in prepared_images:
                    prepared_image.close()

            log.info(
                "prepared %d/%d photos",
                min(batch_start + len(batch), len(catalog)),
                len(catalog),
            )

        manifest = build_manifest(
            template_version=template_version,
            embedding_model=model_id,
            embedding_dimension=embedding_dimension,
            photos=prepared_photos,
        )
        _write_new_file(
            staging / OUTPUT_MANIFEST_NAME,
            serialize_manifest(manifest),
        )
        _fsync_directory(previews_dir)
        _fsync_directory(staging)

        # A second check catches the ordinary concurrent-creator case.  The final rename
        # is same-filesystem and makes the complete directory visible as one operation.
        if _lexists(output):
            raise FileExistsError(f"output directory appeared during preparation: {output}")
        staging.rename(output)
        _fsync_directory(output.parent)
    except BaseException:
        if staging.exists():
            shutil.rmtree(staging)
        raise

    return PreparationResult(
        output_dir=output,
        manifest_path=output / OUTPUT_MANIFEST_NAME,
        preview_count=len(catalog),
    )


def _validate_safe_image_filename(filename: str) -> None:
    if not filename or filename != filename.strip():
        raise ValueError("filename must be non-empty and have no surrounding whitespace")
    if len(filename) > MAX_FILE_NAME_LENGTH:
        raise ValueError(f"filename exceeds {MAX_FILE_NAME_LENGTH} characters")
    if not _SAFE_FILE_NAME.fullmatch(filename) or ".." in filename:
        raise ValueError(f"unsafe filename: {filename!r}")
    suffix = Path(filename).suffix.lower()
    if suffix not in CONTENT_TYPE_BY_SUFFIX:
        allowed = ", ".join(sorted(CONTENT_TYPE_BY_SUFFIX))
        raise ValueError(f"unsupported image extension in {filename!r}; allowed: {allowed}")


def _validate_sha256(value: str, *, label: str) -> None:
    if not _SHA256.fullmatch(value):
        raise ValueError(f"{label} must be 64 lowercase hexadecimal characters")


def _require_unique_names(names: Sequence[str], *, label: str) -> None:
    exact_seen: set[str] = set()
    portable_seen: dict[str, str] = {}
    for name in names:
        if name in exact_seen:
            raise CatalogError(f"duplicate {label}: {name!r}")
        exact_seen.add(name)

        portable = name.casefold()
        previous = portable_seen.get(portable)
        if previous is not None:
            raise CatalogError(
                f"case-insensitive {label} collision: {previous!r} and {name!r}"
            )
        portable_seen[portable] = name


def _scan_source_images(source: Path) -> dict[str, Path]:
    images: dict[str, Path] = {}
    portable_names: dict[str, str] = {}
    try:
        entries = list(source.iterdir())
    except OSError as failure:
        raise CatalogError(f"cannot list source directory {source}: {failure}") from failure

    for entry in entries:
        if entry.suffix.lower() not in CONTENT_TYPE_BY_SUFFIX:
            continue
        try:
            _validate_safe_image_filename(entry.name)
        except ValueError as failure:
            raise CatalogError(f"unsafe source image entry: {failure}") from failure
        _require_regular_file(entry, description="source image")

        portable = entry.name.casefold()
        previous = portable_names.get(portable)
        if previous is not None:
            raise CatalogError(
                f"case-insensitive source image collision: {previous!r} and {entry.name!r}"
            )
        portable_names[portable] = entry.name
        images[entry.name] = entry
    return images


def _require_regular_file(path: Path, *, description: str) -> None:
    try:
        mode = path.lstat().st_mode
    except OSError as failure:
        raise CatalogError(f"{description} is missing or unreadable: {path}") from failure
    if stat.S_ISLNK(mode) or not stat.S_ISREG(mode):
        raise CatalogError(f"{description} must be a regular non-symlink file: {path}")


def _write_new_file(path: Path, data: bytes) -> None:
    with path.open("xb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def _fsync_directory(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _lexists(path: Path) -> bool:
    return os.path.lexists(os.fspath(path))


def _positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def _preview_quality(value: str) -> int:
    parsed = int(value)
    if not 1 <= parsed <= 95:
        raise argparse.ArgumentTypeError("must be in the range 1..95")
    return parsed


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m embedder.mock_gallery",
        description=(
            "Prepare an atomic Mock Gallery manifest and Lambda-equivalent previews. "
            "Originals remain in the source directory."
        ),
    )
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--template-version", required=True)
    parser.add_argument("--model-id", default=DEFAULT_MODEL_ID)
    parser.add_argument(
        "--embedding-dimension",
        type=_positive_int,
        default=DEFAULT_EMBEDDING_DIMENSION,
    )
    parser.add_argument(
        "--resize-long-edge",
        type=_positive_int,
        default=DEFAULT_RESIZE_LONG_EDGE,
    )
    parser.add_argument(
        "--preview-quality",
        type=_preview_quality,
        default=DEFAULT_PREVIEW_QUALITY,
    )
    parser.add_argument(
        "--batch-size",
        type=_positive_int,
        default=DEFAULT_BATCH_SIZE,
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-5s %(name)s | %(message)s",
    )
    try:
        result = prepare_dataset(
            source_dir=args.source_dir,
            output_dir=args.output_dir,
            template_version=args.template_version,
            model_id=args.model_id,
            embedding_dimension=args.embedding_dimension,
            resize_long_edge=args.resize_long_edge,
            preview_quality=args.preview_quality,
            batch_size=args.batch_size,
        )
    except (OSError, ValueError, MockGalleryPreparationError) as failure:
        log.error("Mock Gallery preparation failed: %s", failure)
        return 1

    print(
        json.dumps(
            {
                "outputDir": str(result.output_dir),
                "manifest": str(result.manifest_path),
                "previews": result.preview_count,
                "originalsCopied": False,
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

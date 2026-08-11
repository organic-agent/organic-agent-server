from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path


EMBEDDER_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(EMBEDDER_ROOT))

from embedder import mock_gallery


class CatalogTest(unittest.TestCase):
    def test_catalog_preserves_csv_order_and_requires_exact_image_set(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            source = Path(temporary_directory)
            self._write_manifest(source, ["second.jpeg", "first.png"])
            (source / "first.png").write_bytes(b"not decoded by catalog validation")
            (source / "second.jpeg").write_bytes(b"not decoded by catalog validation")
            (source / "README.md").write_text("companion metadata", encoding="utf-8")

            photos = mock_gallery.read_catalog(source)

            self.assertEqual(["second.jpeg", "first.png"], [p.filename for p in photos])
            self.assertEqual([0, 1], [p.display_order for p in photos])
            self.assertEqual(["second.jpg", "first.jpg"], [p.preview_filename for p in photos])
            self.assertEqual(["image/jpeg", "image/png"], [p.content_type for p in photos])

    def test_catalog_rejects_duplicate_and_case_colliding_filenames(self) -> None:
        cases = [
            ["photo.png", "photo.png"],
            ["photo.png", "PHOTO.PNG"],
        ]
        for filenames in cases:
            with self.subTest(filenames=filenames):
                with tempfile.TemporaryDirectory() as temporary_directory:
                    source = Path(temporary_directory)
                    self._write_manifest(source, filenames)
                    for filename in set(filenames):
                        (source / filename).write_bytes(b"image")

                    with self.assertRaises(mock_gallery.CatalogError):
                        mock_gallery.read_catalog(source)

    def test_catalog_rejects_unsafe_or_nested_filename(self) -> None:
        unsafe_names = ["../photo.png", "nested/photo.png", "/photo.png", " photo.png"]
        for filename in unsafe_names:
            with self.subTest(filename=filename):
                with tempfile.TemporaryDirectory() as temporary_directory:
                    source = Path(temporary_directory)
                    self._write_manifest(source, [filename])

                    with self.assertRaises(mock_gallery.CatalogError):
                        mock_gallery.read_catalog(source)

    def test_catalog_rejects_missing_and_unlisted_images(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            source = Path(temporary_directory)
            self._write_manifest(source, ["listed.png", "missing.png"])
            (source / "listed.png").write_bytes(b"image")
            (source / "unlisted.webp").write_bytes(b"image")

            with self.assertRaisesRegex(
                mock_gallery.CatalogError, "missing=.*missing.png.*unlisted=.*unlisted.webp"
            ):
                mock_gallery.read_catalog(source)

    def test_catalog_rejects_preview_name_collision(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            source = Path(temporary_directory)
            self._write_manifest(source, ["same.png", "same.jpg"])
            (source / "same.png").write_bytes(b"png")
            (source / "same.jpg").write_bytes(b"jpeg")

            with self.assertRaisesRegex(mock_gallery.CatalogError, "preview filename"):
                mock_gallery.read_catalog(source)

    @unittest.skipUnless(hasattr(os, "symlink"), "symlinks are unavailable")
    def test_catalog_rejects_symlinked_image(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            source = Path(temporary_directory)
            self._write_manifest(source, ["linked.png"])
            target = source / "target.bin"
            target.write_bytes(b"image")
            os.symlink(target, source / "linked.png")

            with self.assertRaisesRegex(mock_gallery.CatalogError, "non-symlink"):
                mock_gallery.read_catalog(source)

    def test_catalog_rejects_malformed_csv_width(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            source = Path(temporary_directory)
            (source / mock_gallery.SOURCE_MANIFEST_NAME).write_text(
                "filename,concept\nphoto.png\n", encoding="utf-8"
            )
            (source / "photo.png").write_bytes(b"image")

            with self.assertRaisesRegex(mock_gallery.CatalogError, "expected 2"):
                mock_gallery.read_catalog(source)

    @staticmethod
    def _write_manifest(source: Path, filenames: list[str]) -> None:
        rows = ["filename,set,concept,shot,subjects"]
        rows.extend(f"{filename},set01,concept,shot,subjects" for filename in filenames)
        (source / mock_gallery.SOURCE_MANIFEST_NAME).write_text(
            "\n".join(rows) + "\n", encoding="utf-8"
        )


class ManifestHelperTest(unittest.TestCase):
    def test_manifest_matches_server_schema_and_sorts_by_display_order(self) -> None:
        second = self._prepared_photo(
            filename="second.png",
            display_order=1,
            original_hash="b" * 64,
            preview_hash="c" * 64,
            embedding=(0.0, 1.0, 0.0),
        )
        first = self._prepared_photo(
            filename="first.jpeg",
            display_order=0,
            original_hash="a" * 64,
            preview_hash="d" * 64,
            embedding=(1.0, 0.0, 0.0),
        )

        manifest = mock_gallery.build_manifest(
            template_version="wedding-v1",
            embedding_model="facebook/dinov2-base",
            embedding_dimension=3,
            photos=[second, first],
        )

        self.assertEqual(1, manifest["schemaVersion"])
        self.assertEqual("wedding-v1", manifest["templateVersion"])
        self.assertEqual("facebook/dinov2-base", manifest["embeddingModel"])
        self.assertEqual(3, manifest["embeddingDimension"])
        self.assertEqual(
            ["first.jpeg", "second.png"],
            [photo["originalFileName"] for photo in manifest["photos"]],
        )
        self.assertEqual(
            "mock-gallery/wedding-v1/originals/first.jpeg",
            manifest["photos"][0]["storageKey"],
        )
        self.assertEqual(
            "mock-gallery/wedding-v1/previews/first.jpg",
            manifest["photos"][0]["previewKey"],
        )
        self.assertEqual("image/jpeg", manifest["photos"][0]["contentType"])
        self.assertEqual(0, manifest["photos"][0]["displayOrder"])

        serialized = mock_gallery.serialize_manifest(manifest)
        self.assertTrue(serialized.endswith(b"\n"))
        self.assertEqual(manifest, json.loads(serialized))

    def test_manifest_rejects_wrong_dimension_non_finite_and_non_normalized_vectors(self) -> None:
        cases = [
            ((1.0, 0.0), 3, "has 2 values"),
            ((float("nan"), 0.0, 1.0), 3, "non-finite"),
            ((0.5, 0.0, 0.0), 3, "not L2-normalized"),
        ]
        for embedding, dimension, message in cases:
            with self.subTest(message=message):
                photo = self._prepared_photo(
                    filename="photo.png",
                    display_order=0,
                    original_hash="a" * 64,
                    preview_hash="b" * 64,
                    embedding=embedding,
                )

                with self.assertRaisesRegex(ValueError, message):
                    mock_gallery.build_manifest(
                        template_version="wedding-v1",
                        embedding_model="facebook/dinov2-base",
                        embedding_dimension=dimension,
                        photos=[photo],
                    )

    def test_manifest_rejects_invalid_version_hash_and_display_order(self) -> None:
        photo = self._prepared_photo(
            filename="photo.png",
            display_order=1,
            original_hash="A" * 64,
            preview_hash="b" * 64,
            embedding=(1.0, 0.0, 0.0),
        )

        for invalid_version in ("../unsafe", "v1..2"):
            with self.subTest(invalid_version=invalid_version):
                with self.assertRaisesRegex(ValueError, "template version"):
                    mock_gallery.build_manifest(
                        template_version=invalid_version,
                        embedding_model="facebook/dinov2-base",
                        embedding_dimension=3,
                        photos=[photo],
                    )

        with self.assertRaisesRegex(ValueError, "display orders"):
            mock_gallery.build_manifest(
                template_version="wedding-v1",
                embedding_model="facebook/dinov2-base",
                embedding_dimension=3,
                photos=[photo],
            )

        bad_hash_photo = self._prepared_photo(
            filename="photo.png",
            display_order=0,
            original_hash="A" * 64,
            preview_hash="b" * 64,
            embedding=(1.0, 0.0, 0.0),
        )
        with self.assertRaisesRegex(ValueError, "original SHA-256"):
            mock_gallery.build_manifest(
                template_version="wedding-v1",
                embedding_model="facebook/dinov2-base",
                embedding_dimension=3,
                photos=[bad_hash_photo],
            )

    def test_current_embedder_defaults_are_explicit(self) -> None:
        self.assertEqual("facebook/dinov2-base", mock_gallery.DEFAULT_MODEL_ID)
        self.assertEqual(768, mock_gallery.DEFAULT_EMBEDDING_DIMENSION)
        self.assertEqual(1024, mock_gallery.DEFAULT_RESIZE_LONG_EDGE)
        self.assertEqual(82, mock_gallery.DEFAULT_PREVIEW_QUALITY)
        self.assertEqual(8, mock_gallery.DEFAULT_BATCH_SIZE)

    @staticmethod
    def _prepared_photo(
        *,
        filename: str,
        display_order: int,
        original_hash: str,
        preview_hash: str,
        embedding: tuple[float, ...],
    ) -> mock_gallery.PreparedPhoto:
        content_type = mock_gallery.CONTENT_TYPE_BY_SUFFIX[Path(filename).suffix.lower()]
        source = mock_gallery.CatalogPhoto(
            filename=filename,
            path=Path("/originals") / filename,
            content_type=content_type,
            preview_filename=mock_gallery.preview_filename_for(filename),
            display_order=display_order,
        )
        return mock_gallery.PreparedPhoto(
            source=source,
            original_sha256=original_hash,
            preview_sha256=preview_hash,
            embedding=embedding,
        )


if __name__ == "__main__":
    unittest.main()

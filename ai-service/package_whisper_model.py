"""배포본에 넣을 Whisper 모델의 고정 스냅숏을 내려받는다."""
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

from huggingface_hub import snapshot_download


MODEL_ID = "Systran/faster-whisper-base"
MODEL_REVISION = "ebe41f70d5b6dfa9166e2c581c45c9c0cfc57b66"
MODEL_FILES = ("README.md", "config.json", "model.bin", "tokenizer.json", "vocabulary.txt")


def download(destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    snapshot_download(
        repo_id=MODEL_ID,
        revision=MODEL_REVISION,
        local_dir=destination,
        allow_patterns=list(MODEL_FILES),
    )
    shutil.rmtree(destination / ".cache", ignore_errors=True)

    missing = [name for name in MODEL_FILES if not (destination / name).is_file()]
    if missing:
        raise RuntimeError("Whisper 모델 파일이 빠졌습니다: " + ", ".join(missing))

    metadata = {
        "source": f"https://huggingface.co/{MODEL_ID}",
        "revision": MODEL_REVISION,
        "license": "MIT",
        "files": list(MODEL_FILES),
    }
    (destination / "BUNDLE-METADATA.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    download(args.destination.resolve())


if __name__ == "__main__":
    main()

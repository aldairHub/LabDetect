"""Scan every dataset photo with Florence-2 OCR and rank likely nameplate shots.

The dataset stays outside the Android repository. Only the resumable OCR audit and
its compact candidate report are written under knowledge/photo_audit.
"""

from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict
from pathlib import Path

import torch
from PIL import Image
from transformers import AutoModelForCausalLM, AutoProcessor


MODEL = "microsoft/Florence-2-base-ft"
REVISION = "f6c1a25888ffc1d945ee8a1a77ac833c7303d46e"
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}
SIGNAL_WORDS = re.compile(
    r"(?i)(model|modelo|type|tipo|serial|serie|volt|watt|hz|rpm|ºc|°c|"
    r"memmert|ohaus|parr|lindberg|blue\s*m|lab.?ion|barnstead|electrothermal|"
    r"fisher|biobase|all\s*american|benchmark|brookfield|boeco|atago|hettich|"
    r"selecta|foss|labconco|uvp|thermolyne)"
)


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--device", default="cuda:0")
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--limit", type=int, default=0)
    return parser.parse_args()


def flatten_ocr(result: dict) -> str:
    payload = result.get("<OCR_WITH_REGION>", result)
    labels = payload.get("labels", []) if isinstance(payload, dict) else []
    return " | ".join(str(label).strip() for label in labels if str(label).strip())


def main() -> None:
    args = arguments()
    args.output.mkdir(parents=True, exist_ok=True)
    progress_path = args.output / "ocr_results.jsonl"
    candidates_path = args.output / "nameplate_candidates.json"

    images = sorted(
        path for path in args.dataset.rglob("*")
        if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS
    )
    if args.limit > 0:
        images = images[: args.limit]

    existing: list[dict] = []
    if progress_path.exists():
        existing = [json.loads(line) for line in progress_path.read_text(encoding="utf-8").splitlines() if line]
    completed = {record["source"] for record in existing}
    pending = [path for path in images if str(path) not in completed]

    processor = AutoProcessor.from_pretrained(
        MODEL, revision=REVISION, trust_remote_code=True, cache_dir=args.cache,
        local_files_only=True,
    )
    model = AutoModelForCausalLM.from_pretrained(
        MODEL, revision=REVISION, trust_remote_code=True, cache_dir=args.cache,
        local_files_only=True, torch_dtype=torch.float16,
    ).to(args.device)
    model.eval()

    batch_size = max(1, args.batch_size)
    with progress_path.open("a", encoding="utf-8") as stream:
        for start in range(0, len(pending), batch_size):
            batch_paths = pending[start : start + batch_size]
            batch_images = []
            for path in batch_paths:
                with Image.open(path) as image:
                    batch_images.append(image.convert("RGB"))
            prompts = ["<OCR_WITH_REGION>"] * len(batch_images)
            inputs = processor(text=prompts, images=batch_images, return_tensors="pt", padding=True)
            inputs = {
                key: value.to(args.device, dtype=torch.float16) if value.is_floating_point() else value.to(args.device)
                for key, value in inputs.items()
            }
            with torch.inference_mode():
                generated = model.generate(
                    input_ids=inputs["input_ids"], pixel_values=inputs["pixel_values"],
                    max_new_tokens=384, num_beams=1, do_sample=False,
                )
            texts = processor.batch_decode(generated, skip_special_tokens=False)
            for path, image, text in zip(batch_paths, batch_images, texts, strict=True):
                parsed = processor.post_process_generation(
                    text, task="<OCR_WITH_REGION>", image_size=image.size
                )
                record = {
                    "class_name": path.parent.name,
                    "source": str(path),
                    "ocr": flatten_ocr(parsed),
                    "raw": parsed,
                }
                stream.write(json.dumps(record, ensure_ascii=False) + "\n")
            stream.flush()
            done = len(completed) + min(start + len(batch_paths), len(pending))
            print(f"OCR {done}/{len(images)}", flush=True)

    records = [json.loads(line) for line in progress_path.read_text(encoding="utf-8").splitlines() if line]
    by_class: dict[str, list[dict]] = defaultdict(list)
    for record in records:
        text = record.get("ocr", "")
        score = len(SIGNAL_WORDS.findall(text)) * 20 + min(len(text), 300)
        if re.search(r"[A-Z]{1,5}[- ]?\d{1,6}", text):
            score += 40
        by_class[record["class_name"]].append({**record, "score": score})
    compact = {
        class_name: sorted(rows, key=lambda item: item["score"], reverse=True)[:20]
        for class_name, rows in sorted(by_class.items())
    }
    candidates_path.write_text(json.dumps(compact, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Wrote {candidates_path}", flush=True)


if __name__ == "__main__":
    main()

import argparse
import json
import tempfile
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = ROOT / "knowledge" / "manual_manifest.json"
USER_AGENT = "LabDetect-manual-audit/1.0"


def _targets(document: dict) -> list[tuple[str, Path]]:
    names = document.get("local_files") or [document.get("local_file")]
    urls = document.get("download_urls") or [document.get("download_url") or document.get("source_url")]
    if len(urls) == 1 and len(names) > 1:
        return []
    return [
        (url, (ROOT / "knowledge" / name).resolve())
        for url, name in zip(urls, names)
        if url and name
    ]


def _download(url: str, destination: Path, force: bool) -> str:
    if destination.exists() and not force:
        return "YA EXISTE"
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    destination.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(request, timeout=45) as response:
        with tempfile.NamedTemporaryFile(delete=False, dir=destination.parent, suffix=".part") as output:
            while block := response.read(1024 * 1024):
                output.write(block)
            temporary = Path(output.name)
    try:
        with temporary.open("rb") as handle:
            if handle.read(5) != b"%PDF-":
                return "LA FUENTE NO ENTREGA UN PDF DIRECTO"
        temporary.replace(destination)
        return "DESCARGADO"
    finally:
        temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Descarga solo las fuentes que entregan un PDF real.")
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--variant", action="append")
    args = parser.parse_args()
    requested = set(args.variant or [])
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    for document in manifest["documents"]:
        variant_id = document["variant_id"]
        if requested and variant_id not in requested:
            continue
        targets = _targets(document)
        if not targets:
            print(f"PENDIENTE {variant_id}: no hay descarga PDF directa confirmada")
            continue
        for url, path in targets:
            try:
                result = _download(url, path, args.force)
            except Exception as exc:
                result = f"ERROR {exc}"
            print(f"{result} {variant_id} -> {path.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

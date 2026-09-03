"""Genera el contexto textual compacto que permite consultar todos los equipos sin indexación previa."""

import hashlib
import json
import re
from pathlib import Path

from pypdf import PdfReader


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "knowledge" / "general_manual_manifest.json"
CATALOG = ROOT / "app" / "src" / "main" / "assets" / "equipment_catalog.json"
OUTPUT = ROOT / "knowledge" / "manual_text.json"


def compact(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def main() -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    names = {item["id"]: item["display_name"] for item in catalog["equipment"]}
    equipment = {}
    for equipment_id, relative_path in manifest["equipment_files"].items():
        path = ROOT / "knowledge" / relative_path
        reader = PdfReader(path)
        text = compact(" ".join(page.extract_text() or "" for page in reader.pages))
        if not text:
            raise RuntimeError(f"No se pudo extraer texto de {path}")
        equipment[equipment_id] = {
            "display_name": names.get(equipment_id, equipment_id),
            "text": text,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        }
    OUTPUT.write_text(
        json.dumps({"schema_version": 1, "equipment": equipment}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"Contexto documental creado para {len(equipment)} equipos.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

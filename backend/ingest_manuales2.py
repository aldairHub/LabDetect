"""Indexa Manuales2.zip por equipo para File Search de LabDetect.

Conserva cada PDF completo en OpenAI Storage y sube, al vector store aislado del
equipo, cuatro secciones cortas para recuperar únicamente el tema preguntado.
Los documentos se registran como referencias generales: no se anuncian como
manuales exactos del fabricante.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import shutil
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

from pypdf import PdfReader, PdfWriter


ROOT = Path(__file__).resolve().parents[1]
ENV_PATH = ROOT / "backend" / ".env"
DEFAULT_ZIP = Path.home() / "Downloads" / "Manuales2.zip"
WORK_ROOT = ROOT / "tmp" / "manuales2_file_search"
PROGRESS_PATH = WORK_ROOT / "upload_progress.json"
INDEX_PATH = ROOT / "app" / "src" / "main" / "assets" / "document_index.json"
API_BASE = "https://api.openai.com/v1"

MANUALS = {
    "Manual_DestiladorAgua.pdf": ("destilador_agua", "Destilador de agua"),
    "Manual_DestiladorProteinas.pdf": ("destilador_proteina", "Destilador de proteína"),
    "Manual_Estufa_Memmert.pdf": ("estufa", "Estufa"),
    "Manual_ExtractorFibra_JPSelecta.pdf": ("extractor_fibra", "Extractor de fibra"),
    "Manual_ExtractorGrasa_Labconco.pdf": ("extractor_grasa", "Extractor de grasa"),
    "Manual_Incubadora_Memmert.pdf": ("incubadora", "Incubadora"),
    "Manual_MantaCalentamiento.pdf": ("manta_calentamiento", "Manta de calentamiento"),
    "Manual_MolinoPulverizador_Foss.pdf": ("molino_pulverizador", "Molino pulverizador"),
    "Manual_Mufla_NEY_M525.pdf": ("mufla", "Mufla"),
    "Manual_pHmetro_OHAUS.pdf": ("phmetro", "pHmetro"),
    "Manual_Refractometro_ATAGO_NAR1T.pdf": ("refractometro", "Refractómetro"),
    "Manual_Sorbona.pdf": ("sorbona_cabina_extraccion_gases", "Sorbona / cabina de extracción de gases"),
    "Manual_Viscosimetro_Brookfield.pdf": ("viscosimetro", "Viscosímetro"),
}


def load_env() -> None:
    if not ENV_PATH.is_file():
        return
    for raw in ENV_PATH.read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def api_request(
    method: str,
    path: str,
    payload: dict | None = None,
    body: bytes | None = None,
    content_type: str = "application/json",
) -> dict:
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        f"{API_BASE}{path}",
        data=body,
        method=method,
        headers={
            "Authorization": f"Bearer {os.environ['OPENAI_API_KEY']}",
            "Content-Type": content_type,
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            detail = json.loads(raw).get("error", {}).get("message", raw)
        except json.JSONDecodeError:
            detail = raw
        raise RuntimeError(f"OpenAI HTTP {exc.code}: {detail}") from exc


def upload_file(path: Path) -> str:
    boundary = f"----LabDetect{secrets.token_hex(16)}"
    body = b"".join((
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\nassistants\r\n".encode(),
        (
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{path.name}\"\r\n"
            "Content-Type: application/pdf\r\n\r\n"
        ).encode("utf-8"),
        path.read_bytes(),
        f"\r\n--{boundary}--\r\n".encode(),
    ))
    return api_request(
        "POST",
        "/files",
        body=body,
        content_type=f"multipart/form-data; boundary={boundary}",
    )["id"]


def create_vector_store(equipment_id: str) -> str:
    return api_request("POST", "/vector_stores", {"name": f"LabDetect - equipment - {equipment_id}"})["id"]


def attach_file(vector_store_id: str, file_id: str, equipment_id: str, role: str, pages: str) -> None:
    api_request("POST", f"/vector_stores/{vector_store_id}/files", {
        "file_id": file_id,
        "attributes": {
            "equipment_id": equipment_id,
            "document_scope": "general_reference",
            "document_role": role,
            "pages": pages,
            "source": "manuales2",
        },
        "chunking_strategy": {
            "type": "static",
            "static": {"max_chunk_size_tokens": 600, "chunk_overlap_tokens": 150},
        },
    })


def wait_ready(vector_store_id: str, file_id: str) -> None:
    for _ in range(90):
        status = api_request("GET", f"/vector_stores/{vector_store_id}/files/{file_id}")
        if status.get("status") == "completed":
            return
        if status.get("status") in {"failed", "cancelled"}:
            raise RuntimeError(f"OpenAI no pudo indexar {file_id}: {status.get('last_error')}")
        time.sleep(2)
    raise TimeoutError(f"La indexación de {file_id} tardó demasiado")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def split_pdf(source: Path, destination: Path) -> list[dict]:
    reader = PdfReader(str(source))
    pages = len(reader.pages)
    if pages < 4:
        raise ValueError(f"{source.name} no tiene páginas suficientes para dividirlo")
    destination.mkdir(parents=True, exist_ok=True)
    groups = [
        ("funcion_y_caracteristicas", 1, min(3, pages)),
        ("operacion", 4, min(5, pages)),
        ("seguridad", 5, min(6, pages)),
        ("mantenimiento_y_problemas", 6, pages),
    ]
    sections = []
    for section_id, first, last in groups:
        if first > pages:
            continue
        target = destination / f"{source.stem}__{section_id}_p{first}-{last}.pdf"
        writer = PdfWriter()
        for page_number in range(first - 1, last):
            writer.add_page(reader.pages[page_number])
        with target.open("wb") as handle:
            writer.write(handle)
        sections.append({"id": section_id, "path": target, "pages": f"{first}-{last}"})
    return sections


def extract_zip(zip_path: Path) -> Path:
    originals = WORK_ROOT / "originales"
    if originals.exists():
        shutil.rmtree(originals)
    originals.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as archive:
        for entry in archive.infolist():
            if entry.is_dir() or not entry.filename.lower().endswith(".pdf"):
                continue
            (originals / Path(entry.filename).name).write_bytes(archive.read(entry))
    return originals


def prepare(zip_path: Path) -> list[dict]:
    originals = extract_zip(zip_path)
    sections_root = WORK_ROOT / "secciones"
    if sections_root.exists():
        shutil.rmtree(sections_root)
    prepared = []
    for filename, (equipment_id, display_name) in MANUALS.items():
        source = originals / filename
        if not source.is_file():
            raise FileNotFoundError(f"Falta {filename} dentro del ZIP")
        sections = split_pdf(source, sections_root / equipment_id)
        prepared.append({
            "equipment_id": equipment_id,
            "display_name": display_name,
            "source": source,
            "source_sha256": sha256(source),
            "page_count": len(PdfReader(str(source)).pages),
            "sections": sections,
        })
    return prepared


def load_progress() -> dict:
    if not PROGRESS_PATH.is_file():
        return {"equipment": {}}
    return json.loads(PROGRESS_PATH.read_text(encoding="utf-8"))


def save_progress(progress: dict) -> None:
    PROGRESS_PATH.parent.mkdir(parents=True, exist_ok=True)
    PROGRESS_PATH.write_text(json.dumps(progress, ensure_ascii=False, indent=2), encoding="utf-8")


def upload_document(document: dict, equipment: dict, progress: dict) -> None:
    """Sube un equipo de forma reanudable, sin repetir un archivo ya confirmado."""
    equipment_id = document["equipment_id"]
    pending = progress.setdefault("equipment", {}).setdefault(equipment_id, {})
    if not pending.get("vector_store_id"):
        print(f"CREANDO STORE {equipment_id}...")
        pending["vector_store_id"] = create_vector_store(equipment_id)
        save_progress(progress)
    if not pending.get("full_file_id"):
        print(f"SUBIENDO PDF COMPLETO {equipment_id}...")
        pending["full_file_id"] = upload_file(document["source"])
        save_progress(progress)

    pending_sections = pending.setdefault("sections", {})
    completed_sections = []
    for section in document["sections"]:
        section_id = section["id"]
        state = pending_sections.setdefault(section_id, {"pages": section["pages"]})
        if not state.get("file_id"):
            print(f"SUBIENDO {equipment_id}/{section_id}...")
            state["file_id"] = upload_file(section["path"])
            save_progress(progress)
        if not state.get("attached"):
            print(f"INDEXANDO {equipment_id}/{section_id}...")
            attach_file(pending["vector_store_id"], state["file_id"], equipment_id, section_id, section["pages"])
            wait_ready(pending["vector_store_id"], state["file_id"])
            state["attached"] = True
            save_progress(progress)
        completed_sections.append({
            "id": section_id,
            "file_id": state["file_id"],
            "pages": section["pages"],
        })

    equipment[equipment_id] = {
        "display_name": document["display_name"],
        "vector_store_id": pending["vector_store_id"],
        "full_file_id": pending["full_file_id"],
        "sha256": document["source_sha256"],
        "page_count": document["page_count"],
        "sections": completed_sections,
    }
    del progress["equipment"][equipment_id]
    save_progress(progress)


def main() -> int:
    parser = argparse.ArgumentParser(description="Indexa Manuales2.zip por equipo para LabDetect.")
    parser.add_argument("--zip", type=Path, default=DEFAULT_ZIP)
    parser.add_argument("--prepare-only", action="store_true", help="Solo extrae y divide; no llama a OpenAI.")
    args = parser.parse_args()
    load_env()
    if not args.zip.is_file():
        raise SystemExit(f"No existe el ZIP: {args.zip}")
    if not args.prepare_only and not os.getenv("OPENAI_API_KEY"):
        raise SystemExit("Falta OPENAI_API_KEY en backend/.env")

    prepared = prepare(args.zip)
    if args.prepare_only:
        print(f"PREPARADO: {len(prepared)} manuales y {sum(len(item['sections']) for item in prepared)} secciones")
        return 0

    index = json.loads(INDEX_PATH.read_text(encoding="utf-8"))
    index["source"] = "manuales1.zip + manuales2.zip"
    index["sources"] = ["manuales1.zip", "manuales2.zip"]
    index["document_scope"] = "general_reference"
    index["warning"] = "Los documentos son referencias generales; no sustituyen el manual exacto del modelo."
    equipment = index.setdefault("equipment", {})
    progress = load_progress()
    for document in prepared:
        equipment_id = document["equipment_id"]
        previous = equipment.get(equipment_id)
        if previous:
            same_document = (
                previous.get("sha256") == document["source_sha256"]
                and previous.get("page_count") == document["page_count"]
                and len(previous.get("sections", [])) == len(document["sections"])
                and bool(previous.get("vector_store_id"))
                and bool(previous.get("full_file_id"))
            )
            if same_document:
                print(f"YA INDEXADO {equipment_id}; se omite sin duplicar.")
                continue
            raise RuntimeError(f"{equipment_id} ya tiene otro índice. No se sobrescribe sin revisión.")
        print(f"SUBIENDO {equipment_id}...")
        upload_document(document, equipment, progress)
        INDEX_PATH.write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"LISTO {equipment_id}: PDF completo + {len(document['sections'])} secciones")
    print(f"COMPLETADO: {len(prepared)} PDF completos y {sum(len(item['sections']) for item in prepared)} secciones indexadas.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

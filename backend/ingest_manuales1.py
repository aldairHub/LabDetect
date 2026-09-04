"""Divide e indexa manuales1.zip por equipo para File Search.

Los PDFs originales quedan cargados como documentos completos y, adicionalmente,
cada sección se sube como documento independiente. El índice producido se guarda
en assets para que la APK seleccione el vector store del equipo sin una base de datos.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

from pypdf import PdfReader, PdfWriter


ROOT = Path(__file__).resolve().parents[1]
ENV_PATH = ROOT / "backend" / ".env"
DEFAULT_ZIP = Path.home() / "Downloads" / "manuales1.zip"
WORK_ROOT = ROOT / "tmp" / "manuales1_file_search"
INDEX_PATH = ROOT / "app" / "src" / "main" / "assets" / "document_index.json"
API_BASE = "https://api.openai.com/v1"

MANUALS = {
    "Manual_AgitadorCalentador_Heidolph.pdf": ("agitador_calentador", "Agitador calentador"),
    "Manual_Autoclave.pdf": ("autoclave", "Autoclave"),
    "Manual_BalanzaAnalitica_OHAUS.pdf": ("balanza_analitica", "Balanza analítica"),
    "Manual_BanoMaria.pdf": ("bano_maria", "Baño María"),
    "Manual_BloqueDigestor_JPSelecta.pdf": ("bloque_digestor", "Bloque digestor"),
    "Manual_BombaVacio_JPSelecta.pdf": ("bomba_vacio", "Bomba de vacío"),
    "Manual_CabinaFlujoLaminar.pdf": ("cabina_flujo_laminar", "Cabina de flujo laminar"),
    "Manual_Calentador.pdf": ("calentador", "Calentador"),
    "Manual_Calorimetro_Parr.pdf": ("calorimetro", "Calorímetro"),
    "Manual_Centrifuga_Rotofix.pdf": ("centrifuga", "Centrífuga"),
    "Manual_ContadorColonias.pdf": ("contador_colonias", "Contador de colonias"),
    "Manual_Desecador.pdf": ("desecador", "Desecador"),
    "Manual_DesmineralizadorAgua.pdf": ("desmineralizador_agua", "Desmineralizador de agua"),
}


def load_env() -> None:
    if not ENV_PATH.is_file():
        return
    for raw in ENV_PATH.read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def api_request(method: str, path: str, payload: dict | None = None, body: bytes | None = None,
                content_type: str = "application/json") -> dict:
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
        (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{path.name}\"\r\n"
         "Content-Type: application/pdf\r\n\r\n").encode("utf-8"),
        path.read_bytes(),
        f"\r\n--{boundary}--\r\n".encode(),
    ))
    return api_request("POST", "/files", body=body,
                       content_type=f"multipart/form-data; boundary={boundary}")["id"]


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
            "source": "manuales1",
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
    destination.mkdir(parents=True, exist_ok=True)
    pages = len(reader.pages)
    groups = [
        ("funcion_y_caracteristicas", 1, min(3, pages)),
        ("operacion", 4, min(5, pages)),
        # La página de transición se conserva en ambos documentos para que las
        # advertencias que introducen una operación no se pierdan al consultar seguridad.
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
    extracted = WORK_ROOT / "originales"
    extracted.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as archive:
        for entry in archive.infolist():
            if entry.is_dir() or not entry.filename.lower().endswith(".pdf"):
                continue
            target = extracted / Path(entry.filename).name
            target.write_bytes(archive.read(entry))
    return extracted


def replace_question_sections(zip_path: Path) -> int:
    """Reemplaza las secciones de File Search sin borrar los PDF completos de Storage."""
    originals = extract_zip(zip_path)
    sections_root = WORK_ROOT / "secciones"
    index = json.loads(INDEX_PATH.read_text(encoding="utf-8"))
    for filename, (equipment_id, _) in MANUALS.items():
        document = index.get("equipment", {}).get(equipment_id)
        if not document:
            raise RuntimeError(f"No existe un índice previo para {equipment_id}")
        source = originals / filename
        sections = split_pdf(source, sections_root / equipment_id)
        for old_section in document.get("sections", []):
            api_request("DELETE", f"/vector_stores/{document['vector_store_id']}/files/{old_section['file_id']}")
        updated_sections = []
        for section in sections:
            file_id = upload_file(section["path"])
            attach_file(document["vector_store_id"], file_id, equipment_id, section["id"], section["pages"])
            wait_ready(document["vector_store_id"], file_id)
            updated_sections.append({"id": section["id"], "file_id": file_id, "pages": section["pages"]})
        document["sections"] = updated_sections
        INDEX_PATH.write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"REINDEXADO {equipment_id}: {len(updated_sections)} secciones por pregunta")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Indexa manuales1.zip por equipo para LabDetect.")
    parser.add_argument("--zip", type=Path, default=DEFAULT_ZIP)
    parser.add_argument("--prepare-only", action="store_true", help="Solo extrae y divide; no llama a OpenAI.")
    parser.add_argument("--detach-complete", action="store_true", help="Quita del índice los PDF completos ya subidos.")
    parser.add_argument("--reindex-sections", action="store_true", help="Reemplaza secciones por bloques de pregunta.")
    args = parser.parse_args()
    load_env()
    if args.detach_complete:
        if not os.getenv("OPENAI_API_KEY"):
            raise SystemExit("Falta OPENAI_API_KEY en backend/.env")
        index = json.loads(INDEX_PATH.read_text(encoding="utf-8"))
        for equipment_id, document in index.get("equipment", {}).items():
            api_request("DELETE", f"/vector_stores/{document['vector_store_id']}/files/{document['full_file_id']}")
            print(f"PDF completo separado del índice: {equipment_id}")
        return 0
    if not args.zip.is_file():
        raise SystemExit(f"No existe el ZIP: {args.zip}")
    if args.reindex_sections:
        if not os.getenv("OPENAI_API_KEY"):
            raise SystemExit("Falta OPENAI_API_KEY en backend/.env")
        return replace_question_sections(args.zip)
    if not args.prepare_only and not os.getenv("OPENAI_API_KEY"):
        raise SystemExit("Falta OPENAI_API_KEY en backend/.env")

    originals = extract_zip(args.zip)
    sections_root = WORK_ROOT / "secciones"
    sections_root.mkdir(parents=True, exist_ok=True)
    prepared: list[dict] = []
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
    if args.prepare_only:
        print(f"PREPARADO: {len(prepared)} manuales y {sum(len(item['sections']) for item in prepared)} secciones")
        return 0

    index = {
        "schema_version": 1,
        "source": "manuales1.zip",
        "document_scope": "general_reference",
        "warning": "Los documentos indican que son referencias generales; no sustituyen el manual exacto del modelo.",
        "equipment": {},
    }
    for document in prepared:
        equipment_id = document["equipment_id"]
        print(f"SUBIENDO {equipment_id}...")
        vector_store_id = create_vector_store(equipment_id)
        # Se conserva el PDF completo en Storage para auditoría/manual íntegro, pero no
        # se indexa en File Search. Así la recuperación usa solo las secciones pequeñas.
        full_file_id = upload_file(document["source"])
        section_entries = []
        for section in document["sections"]:
            file_id = upload_file(section["path"])
            attach_file(vector_store_id, file_id, equipment_id, section["id"], section["pages"])
            wait_ready(vector_store_id, file_id)
            section_entries.append({
                "id": section["id"],
                "file_id": file_id,
                "pages": section["pages"],
            })
        index["equipment"][equipment_id] = {
            "display_name": document["display_name"],
            "vector_store_id": vector_store_id,
            "full_file_id": full_file_id,
            "sha256": document["source_sha256"],
            "page_count": document["page_count"],
            "sections": section_entries,
        }
        INDEX_PATH.write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"LISTO {equipment_id}: {len(section_entries)} secciones")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

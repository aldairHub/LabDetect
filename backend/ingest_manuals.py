import argparse
import hashlib
import json
import os
import secrets
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENV_PATH = ROOT / "backend" / ".env"
MANIFEST_PATH = ROOT / "knowledge" / "manual_manifest.json"
GENERAL_MANIFEST_PATH = ROOT / "knowledge" / "general_manual_manifest.json"
REGISTRY_PATH = ROOT / "backend" / "vector_stores.json"
API_BASE = "https://api.openai.com/v1"


def _load_env() -> None:
    if not ENV_PATH.is_file():
        return
    for raw_line in ENV_PATH.read_text(encoding="utf-8-sig").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def _api_request(method: str, path: str, payload: dict | None = None, body: bytes | None = None,
                 content_type: str = "application/json") -> dict:
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        f"{API_BASE}{path}",
        data=body,
        headers={
            "Authorization": f"Bearer {os.environ['OPENAI_API_KEY']}",
            "Content-Type": content_type,
        },
        method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            message = json.loads(raw).get("error", {}).get("message", raw)
        except json.JSONDecodeError:
            message = raw
        raise RuntimeError(f"OpenAI HTTP {exc.code}: {message}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(
            "No se pudo conectar con OpenAI. Comprueba Internet y vuelve a ejecutar "
            "ACTIVAR_IA_DOCUMENTAL.cmd."
        ) from exc


def _create_vector_store(name: str) -> str:
    return _api_request("POST", "/vector_stores", {"name": name})["id"]


def _upload_file(path: Path) -> str:
    boundary = f"----LabDetect{secrets.token_hex(16)}"
    filename = path.name.replace('"', "")
    parts = [
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\nassistants\r\n".encode(),
        (
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
            f"filename=\"{filename}\"\r\nContent-Type: application/pdf\r\n\r\n"
        ).encode("utf-8"),
        path.read_bytes(),
        f"\r\n--{boundary}--\r\n".encode(),
    ]
    result = _api_request(
        "POST",
        "/files",
        body=b"".join(parts),
        content_type=f"multipart/form-data; boundary={boundary}",
    )
    return result["id"]


def _attach_file(vector_store_id: str, file_id: str, variant_id: str, equipment_id: str) -> None:
    _api_request(
        "POST",
        f"/vector_stores/{vector_store_id}/files",
        {
            "file_id": file_id,
            "attributes": {
                "variant_id": variant_id,
                "equipment_id": equipment_id,
                "document_scope": "general_or_verified",
            },
        },
    )


def _pdf_paths(document: dict, general_files: dict[str, str]) -> list[Path]:
    names: list[str] = []
    if document.get("status") == "verified":
        names.extend(document.get("local_files") or [document.get("local_file")])
    general_name = general_files.get(document["equipment_id"])
    if general_name:
        names.append(general_name)
    paths = [(ROOT / "knowledge" / name).resolve() for name in names if name]
    # Un manual oficial pendiente no debe impedir usar la referencia general.
    return [path for path in paths if path.is_file()]


def _validate_pdf(path: Path) -> str:
    if not path.is_file():
        raise FileNotFoundError(path)
    with path.open("rb") as handle:
        if handle.read(5) != b"%PDF-":
            raise ValueError(f"No es un PDF válido: {path}")
        handle.seek(0)
        digest = hashlib.sha256()
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _wait_until_ready(vector_store_id: str, file_id: str) -> None:
    for _ in range(120):
        current = _api_request("GET", f"/vector_stores/{vector_store_id}/files/{file_id}")
        if current.get("status") == "completed":
            return
        if current.get("status") == "failed":
            raise RuntimeError(f"OpenAI no pudo procesar {file_id}")
        time.sleep(2)
    raise TimeoutError(f"Tiempo agotado al procesar {file_id}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Indexa manuales exactos y referencias generales aisladas por equipo.")
    parser.add_argument("--variant", action="append", help="ID de variante; se puede repetir")
    args = parser.parse_args()
    _load_env()
    if not os.getenv("OPENAI_API_KEY"):
        raise SystemExit("Falta OPENAI_API_KEY en el entorno del servidor.")

    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    general_manifest = json.loads(GENERAL_MANIFEST_PATH.read_text(encoding="utf-8"))
    general_files = general_manifest.get("equipment_files", {})
    requested = set(args.variant or [])
    documents = [
        item for item in manifest["documents"]
        if (item.get("status") == "verified" or item.get("equipment_id") in general_files)
        and (not requested or item["variant_id"] in requested)
    ]
    registry = {"schema_version": 1, "variants": {}}
    if REGISTRY_PATH.is_file():
        registry = json.loads(REGISTRY_PATH.read_text(encoding="utf-8"))

    for document in documents:
        variant_id = document["variant_id"]
        paths = _pdf_paths(document, general_files)
        if not paths:
            print(f"OMITIDO {variant_id}: no hay PDF local disponible")
            continue
        try:
            hashes = [_validate_pdf(path) for path in paths]
        except (FileNotFoundError, ValueError) as exc:
            print(f"OMITIDO {variant_id}: {exc}")
            continue

        previous = registry["variants"].get(variant_id, {})
        relative_paths = [str(path.relative_to(ROOT)).replace("\\", "/") for path in paths]
        previous_hashes = previous.get("sha256") or []
        if isinstance(previous_hashes, str):
            previous_hashes = [previous_hashes]
        previous_files = [str(path).replace("\\", "/") for path in previous.get("files", [])]
        known_hashes = dict(zip(previous_files, previous_hashes))
        pending = [
            (path, digest, relative)
            for path, digest, relative in zip(paths, hashes, relative_paths)
            if known_hashes.get(relative) != digest
        ]
        if not pending and previous.get("status") == "ready":
            print(f"SIN CAMBIOS {variant_id}")
            continue

        if previous.get("status") == "ready" and previous.get("vector_store_id"):
            vector_store_id = previous["vector_store_id"]
            file_ids = list(previous.get("file_ids", []))
        else:
            vector_store_id = _create_vector_store(name=f"LabDetect - {variant_id}")
            file_ids = []
        for path, _, _ in pending:
            uploaded_id = _upload_file(path)
            _attach_file(vector_store_id, uploaded_id, variant_id, document["equipment_id"])
            _wait_until_ready(vector_store_id, uploaded_id)
            file_ids.append(uploaded_id)

        registry["variants"][variant_id] = {
            "status": "ready",
            "vector_store_id": vector_store_id,
            "file_ids": file_ids,
            "sha256": hashes,
            "files": relative_paths,
            "has_general_reference": document["equipment_id"] in general_files,
            "has_exact_manual": document.get("status") == "verified" and len(paths) > 1,
        }
        REGISTRY_PATH.write_text(json.dumps(registry, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"LISTO {variant_id}: {len(paths)} PDF")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        raise SystemExit(str(exc)) from exc

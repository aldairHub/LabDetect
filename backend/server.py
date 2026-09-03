"""Servidor local de LabDetect sin dependencias externas de Python."""

import json
import os
import re
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ENV_PATH = ROOT / "backend" / ".env"
REGISTRY_PATH = ROOT / "backend" / "vector_stores.json"
NO_DOCUMENTATION = "Eso no aparece en la documentación disponible de este equipo."
VARIANT_PATTERN = re.compile(r"^[a-z0-9_]{3,120}$")


def load_env() -> None:
    if not ENV_PATH.is_file():
        return
    for raw_line in ENV_PATH.read_text(encoding="utf-8-sig").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def registry() -> dict:
    if not REGISTRY_PATH.is_file():
        return {}
    return json.loads(REGISTRY_PATH.read_text(encoding="utf-8")).get("variants", {})


def output_text(response: dict) -> str:
    chunks = []
    for item in response.get("output", []):
        if item.get("type") != "message":
            continue
        for content in item.get("content", []):
            if content.get("type") == "output_text" and content.get("text"):
                chunks.append(content["text"])
    return "\n".join(chunks).strip()


def has_retrieved_content(response: dict) -> bool:
    return any(
        item.get("type") == "file_search_call" and item.get("results")
        for item in response.get("output", [])
    )


def ask_openai(variant_id: str, question: str) -> str:
    entry = registry().get(variant_id)
    if not entry or entry.get("status") != "ready" or not entry.get("vector_store_id"):
        return NO_DOCUMENTATION

    api_key = os.getenv("OPENAI_API_KEY", "")
    if not api_key:
        raise RuntimeError("La clave de OpenAI no está configurada.")
    payload = {
        "model": os.getenv("OPENAI_MODEL", "gpt-5.4-mini"),
        "instructions": (
            "Eres el asistente de voz del Laboratorio de Bromatología. Responde en español natural, "
            "breve y conversacional. Contesta exclusivamente con información recuperada mediante "
            "file_search del PDF del equipo seleccionado. No uses conocimiento general ni supongas. "
            f"Si la documentación no respalda la respuesta, responde exactamente: '{NO_DOCUMENTATION}'. "
            "No menciones archivos, citas, fuentes ni el proceso de búsqueda. No inventes valores, "
            "botones, pasos ni advertencias. Si el documento es una referencia general, no presentes "
            "sus rangos típicos como especificaciones del modelo detectado."
        ),
        "input": question,
        "tools": [{
            "type": "file_search",
            "vector_store_ids": [entry["vector_store_id"]],
            "max_num_results": 8,
        }],
        "tool_choice": "required",
        "include": ["file_search_call.results"],
        "max_output_tokens": 350,
    }
    request = urllib.request.Request(
        "https://api.openai.com/v1/responses",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        result = json.loads(response.read().decode("utf-8"))
    if not has_retrieved_content(result):
        return NO_DOCUMENTATION
    return output_text(result) or NO_DOCUMENTATION


class Handler(BaseHTTPRequestHandler):
    server_version = "LabDetect/1.0"

    def send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if self.path != "/health":
            self.send_json(404, {"detail": "Ruta no encontrada."})
            return
        self.send_json(200, {
            "ok": True,
            "openai_configured": bool(os.getenv("OPENAI_API_KEY")),
            "indexed_variants": len(registry()),
        })

    def do_POST(self) -> None:
        if self.path != "/v1/equipment/ask":
            self.send_json(404, {"detail": "Ruta no encontrada."})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 16_384:
                raise ValueError("Solicitud inválida.")
            data = json.loads(self.rfile.read(length).decode("utf-8"))
            variant_id = str(data.get("variant_id", "")).strip()
            question = str(data.get("question", "")).strip()
            if not VARIANT_PATTERN.fullmatch(variant_id) or not 2 <= len(question) <= 1000:
                raise ValueError("Equipo o pregunta inválidos.")
            self.send_json(200, {"answer": ask_openai(variant_id, question)})
        except ValueError as exc:
            self.send_json(400, {"detail": str(exc)})
        except urllib.error.HTTPError as exc:
            detail = "OpenAI rechazó temporalmente la consulta. Revisa el saldo y la clave del proyecto."
            self.send_json(503, {"detail": detail})
        except Exception:
            self.send_json(503, {"detail": "La base documental no está disponible en este momento."})

    def log_message(self, fmt: str, *args) -> None:
        print(f"{self.address_string()} - {fmt % args}")


if __name__ == "__main__":
    load_env()
    host = os.getenv("LABDETECT_HOST", "0.0.0.0")
    port = int(os.getenv("LABDETECT_PORT", "8000"))
    print(f"LabDetect IA disponible en http://{host}:{port}")
    ThreadingHTTPServer((host, port), Handler).serve_forever()

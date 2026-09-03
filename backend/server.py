"""Servidor local de LabDetect sin dependencias externas de Python."""

import html
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
MANUAL_TEXT_PATH = ROOT / "knowledge" / "manual_text.json"
CATALOG_PATH = ROOT / "app" / "src" / "main" / "assets" / "equipment_catalog.json"
ID_PATTERN = re.compile(r"^[a-z0-9_]{3,120}$")
ALLOWED_VOICES = {
    "es-EC-AndreaNeural",
    "es-EC-LuisNeural",
    "es-MX-DaliaNeural",
}
OPENAI_VOICE_MAP = {
    "es-EC-AndreaNeural": "coral",
    "es-EC-LuisNeural": "onyx",
    "es-MX-DaliaNeural": "nova",
}
OFF_TOPIC = "Puedo ayudarte únicamente con el equipo que estás enfocando."


def load_env() -> None:
    if not ENV_PATH.is_file():
        return
    for raw_line in ENV_PATH.read_text(encoding="utf-8-sig").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def _read_json(path: Path, default: dict) -> dict:
    if not path.is_file():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def registry() -> dict:
    return _read_json(REGISTRY_PATH, {}).get("variants", {})


def manual_entries() -> dict:
    return _read_json(MANUAL_TEXT_PATH, {}).get("equipment", {})


def catalog_entries() -> dict:
    catalog = _read_json(CATALOG_PATH, {}).get("equipment", [])
    return {item["id"]: item for item in catalog}


def output_text(response: dict) -> str:
    chunks = []
    for item in response.get("output", []):
        if item.get("type") != "message":
            continue
        for content in item.get("content", []):
            if content.get("type") == "output_text" and content.get("text"):
                chunks.append(content["text"])
    return "\n".join(chunks).strip()


def _responses_request(payload: dict) -> dict:
    request = urllib.request.Request(
        "https://api.openai.com/v1/responses",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {os.environ['OPENAI_API_KEY']}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=75) as response:
        return json.loads(response.read().decode("utf-8"))


def _equipment_description(equipment_id: str, variant_id: str | None) -> str:
    item = catalog_entries().get(equipment_id, {})
    base = item.get("display_name", equipment_id.replace("_", " "))
    if variant_id:
        variant = next((v for v in item.get("variants", []) if v.get("id") == variant_id), None)
        if variant:
            return f"{base}, {variant.get('display_name', variant_id)}"
    return base


def ask_openai(equipment_id: str, variant_id: str | None, question: str) -> str:
    manual = manual_entries().get(equipment_id)
    if not manual:
        raise ValueError("No existe documentación para el equipo detectado.")
    if not os.getenv("OPENAI_API_KEY"):
        raise RuntimeError("La conexión con OpenAI no está configurada.")

    equipment = _equipment_description(equipment_id, variant_id)
    instructions = (
        "Eres el asistente de voz del Laboratorio de Bromatología. Hablas en español latino natural, "
        "cálido y técnico, como una persona que acompaña al usuario frente al equipo. Responde solamente "
        f"sobre este equipo: {equipment}. Si preguntan por otro tema, responde exactamente: '{OFF_TOPIC}' "
        "No menciones archivos, fuentes, búsquedas, variantes ni procesos internos. No uses Markdown, títulos, "
        "viñetas, enlaces ni citas. Da una respuesta directa de dos a cuatro oraciones y máximo noventa palabras, "
        "redactada para sonar bien al leerla en voz alta. Usa el manual incluido como fuente principal. Puedes "
        "completar con información técnica confiable o búsqueda web solo cuando haga falta, pero nunca inventes "
        "botones, valores o procedimientos específicos del modelo. Para acciones peligrosas, indica la precaución "
        "esencial de forma breve. No digas que existen varios tipos ni pidas al usuario escoger una variante."
    )
    prompt = (
        f"PREGUNTA EXACTA DEL USUARIO:\n{question}\n\n"
        f"CONTEXTO DEL MANUAL DE {equipment.upper()}:\n{manual['text']}"
    )
    variant_entry = registry().get(variant_id or "", {})
    tools = [{"type": "web_search"}]
    if variant_entry.get("status") == "ready" and variant_entry.get("vector_store_id"):
        tools.insert(0, {
            "type": "file_search",
            "vector_store_ids": [variant_entry["vector_store_id"]],
            "max_num_results": 6,
        })
    payload = {
        "model": os.getenv("OPENAI_MODEL", "gpt-5.4-mini"),
        "instructions": instructions,
        "input": prompt,
        "tools": tools,
        "tool_choice": "auto",
        "max_output_tokens": 220,
    }
    try:
        result = _responses_request(payload)
    except urllib.error.HTTPError as exc:
        # Mantiene la conversación operativa si la cuenta/modelo aún no tiene Web Search.
        if exc.code != 400:
            raise
        payload.pop("tools", None)
        payload.pop("tool_choice", None)
        result = _responses_request(payload)
    answer = output_text(result)
    if not answer:
        raise RuntimeError("OpenAI devolvió una respuesta vacía.")
    return answer


def synthesize_speech(text: str, voice: str) -> bytes:
    key = os.getenv("AZURE_SPEECH_KEY", "").strip()
    region = os.getenv("AZURE_SPEECH_REGION", "").strip()
    if voice not in ALLOWED_VOICES:
        voice = "es-EC-AndreaNeural"
    if key and region:
        ssml = (
            "<speak version='1.0' xml:lang='es-EC'>"
            f"<voice name='{voice}'><prosody rate='-2%'>{html.escape(text)}</prosody></voice></speak>"
        ).encode("utf-8")
        request = urllib.request.Request(
            f"https://{region}.tts.speech.microsoft.com/cognitiveservices/v1",
            data=ssml,
            headers={
                "Ocp-Apim-Subscription-Key": key,
                "Content-Type": "application/ssml+xml",
                "X-Microsoft-OutputFormat": "audio-24khz-48kbitrate-mono-mp3",
                "User-Agent": "LabDetect",
            },
            method="POST",
        )
    else:
        api_key = os.getenv("OPENAI_API_KEY", "").strip()
        if not api_key:
            raise RuntimeError("No hay proveedor de voz configurado.")
        payload = {
            "model": os.getenv("OPENAI_TTS_MODEL", "gpt-4o-mini-tts"),
            "voice": OPENAI_VOICE_MAP[voice],
            "input": text,
            "instructions": (
                "Habla en español latino ecuatoriano, con tono cercano, técnico y tranquilo. "
                "Pronuncia con claridad, usa un ritmo conversacional y evita sonar como un anuncio."
            ),
            "response_format": "mp3",
        }
        request = urllib.request.Request(
            "https://api.openai.com/v1/audio/speech",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
    with urllib.request.urlopen(request, timeout=45) as response:
        return response.read()


class Handler(BaseHTTPRequestHandler):
    server_version = "LabDetect/2.0"

    def send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_audio(self, audio: bytes) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "audio/mpeg")
        self.send_header("Content-Length", str(len(audio)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(audio)

    def do_GET(self) -> None:
        if self.path != "/health":
            self.send_json(404, {"detail": "Ruta no encontrada."})
            return
        self.send_json(200, {
            "ok": True,
            "openai_configured": bool(os.getenv("OPENAI_API_KEY")),
            "azure_speech_configured": bool(
                os.getenv("AZURE_SPEECH_KEY") and os.getenv("AZURE_SPEECH_REGION")
            ),
            "cloud_speech_configured": bool(
                os.getenv("OPENAI_API_KEY") or
                (os.getenv("AZURE_SPEECH_KEY") and os.getenv("AZURE_SPEECH_REGION"))
            ),
            "documented_equipment": len(manual_entries()),
            "indexed_variants": len(registry()),
        })

    def do_POST(self) -> None:
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 32_768:
                raise ValueError("Solicitud inválida.")
            data = json.loads(self.rfile.read(length).decode("utf-8"))
            if self.path == "/v1/equipment/ask":
                equipment_id = str(data.get("equipment_id", "")).strip()
                variant_id = str(data.get("variant_id", "")).strip() or None
                question = str(data.get("question", "")).strip()
                if not ID_PATTERN.fullmatch(equipment_id):
                    raise ValueError("Equipo inválido.")
                if variant_id and not ID_PATTERN.fullmatch(variant_id):
                    raise ValueError("Variante inválida.")
                if not 2 <= len(question) <= 1_000:
                    raise ValueError("Pregunta inválida.")
                self.send_json(200, {"answer": ask_openai(equipment_id, variant_id, question)})
                return
            if self.path == "/v1/speech":
                text = str(data.get("text", "")).strip()
                voice = str(data.get("voice", "es-EC-AndreaNeural")).strip()
                if not 1 <= len(text) <= 1_200:
                    raise ValueError("Texto de voz inválido.")
                self.send_audio(synthesize_speech(text, voice))
                return
            self.send_json(404, {"detail": "Ruta no encontrada."})
        except ValueError as exc:
            self.send_json(400, {"detail": str(exc)})
        except urllib.error.HTTPError:
            self.send_json(503, {"detail": "El servicio de IA rechazó temporalmente la consulta."})
        except Exception as exc:
            print(f"Error: {exc}")
            self.send_json(503, {"detail": "El asistente no está disponible en este momento."})

    def log_message(self, fmt: str, *args) -> None:
        print(f"{self.address_string()} - {fmt % args}")


if __name__ == "__main__":
    load_env()
    host = os.getenv("LABDETECT_HOST", "0.0.0.0")
    port = int(os.getenv("LABDETECT_PORT", "8000"))
    print(f"LabDetect IA disponible en http://{host}:{port}")
    ThreadingHTTPServer((host, port), Handler).serve_forever()

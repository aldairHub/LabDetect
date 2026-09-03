from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, Field

from backend.server import ask_openai, load_env, manual_entries, registry, synthesize_speech


load_env()
app = FastAPI(title="LabDetect Knowledge API", version="2.0.0")


class AskRequest(BaseModel):
    equipment_id: str = Field(min_length=3, max_length=120, pattern=r"^[a-z0-9_]+$")
    variant_id: str | None = Field(default=None, min_length=3, max_length=120, pattern=r"^[a-z0-9_]+$")
    question: str = Field(min_length=2, max_length=1_000)


class AskResponse(BaseModel):
    answer: str


class SpeechRequest(BaseModel):
    text: str = Field(min_length=1, max_length=1_200)
    voice: str = Field(default="es-EC-AndreaNeural", min_length=3, max_length=80)


@app.get("/health")
def health() -> dict:
    import os

    return {
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
    }


@app.post("/v1/equipment/ask", response_model=AskResponse)
def ask_equipment(request: AskRequest) -> AskResponse:
    try:
        return AskResponse(
            answer=ask_openai(request.equipment_id, request.variant_id, request.question)
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=503, detail="El asistente no está disponible en este momento.") from exc


@app.post("/v1/speech")
def speech(request: SpeechRequest) -> Response:
    try:
        audio = synthesize_speech(request.text, request.voice)
        return Response(content=audio, media_type="audio/mpeg", headers={"Cache-Control": "no-store"})
    except Exception as exc:
        raise HTTPException(status_code=503, detail="La voz neuronal no está disponible.") from exc

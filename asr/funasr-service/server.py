import os
import tempfile
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import JSONResponse


app = FastAPI(title="RAG Local FunASR Service", version="1.0.0")

_model = None


def model_config() -> dict[str, str]:
    return {
        "model": os.getenv("FUNASR_MODEL", "paraformer-zh"),
        "vad_model": os.getenv("FUNASR_VAD_MODEL", "fsmn-vad"),
        "punc_model": os.getenv("FUNASR_PUNC_MODEL", "ct-punc"),
    }


def get_model():
    global _model
    if _model is not None:
        return _model

    try:
        from funasr import AutoModel
    except Exception as exc:
        raise RuntimeError(
            "FunASR is not installed. Run `python -m pip install -r requirements.txt` first."
        ) from exc

    config = model_config()
    _model = AutoModel(
        model=config["model"],
        vad_model=config["vad_model"],
        punc_model=config["punc_model"],
        disable_update=True,
    )
    return _model


def normalize_segments(result: Any) -> list[dict[str, Any]]:
    if not isinstance(result, list):
        return []

    segments: list[dict[str, Any]] = []
    for item in result:
        if not isinstance(item, dict):
            continue
        sentence_info = item.get("sentence_info")
        if isinstance(sentence_info, list):
            for sentence in sentence_info:
                if not isinstance(sentence, dict):
                    continue
                text = str(sentence.get("text", "")).strip()
                if not text:
                    continue
                segments.append(
                    {
                        "startTimeMs": int(sentence.get("start", -1) or -1),
                        "endTimeMs": int(sentence.get("end", -1) or -1),
                        "text": text,
                    }
                )

    if segments:
        return segments

    for item in result:
        if not isinstance(item, dict):
            continue
        text = str(item.get("text", "")).strip()
        if text:
            segments.append({"startTimeMs": -1, "endTimeMs": -1, "text": text})

    return segments


@app.get("/health")
def health():
    return {"status": "ok", "models": model_config()}


@app.post("/transcribe")
async def transcribe(file: UploadFile = File(...)):
    suffix = Path(file.filename or "audio.wav").suffix or ".wav"
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temp_file:
            temp_path = temp_file.name
            temp_file.write(await file.read())

        model = get_model()
        result = model.generate(input=temp_path, batch_size_s=300)
        segments = normalize_segments(result)
        text = "\n".join(segment["text"] for segment in segments if segment.get("text")).strip()

        return JSONResponse(
            {
                "text": text,
                "segments": segments,
                "raw": result,
            }
        )
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    finally:
        if temp_path:
            try:
                os.remove(temp_path)
            except OSError:
                pass


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "server:app",
        host=os.getenv("FUNASR_HOST", "127.0.0.1"),
        port=int(os.getenv("FUNASR_PORT", "9880")),
        reload=False,
    )

# Local FunASR Service

This service exposes a local ASR endpoint for the RAG backend.

## Start

Use Python 3.9-3.11 on Windows. Python 3.13 may fail when building native
dependencies such as `editdistance`.

```powershell
cd F:\RAG\RAG\asr\funasr-service
.\start.ps1
```

Health check:

```text
http://127.0.0.1:9880/health
```

Transcription endpoint:

```text
POST http://127.0.0.1:9880/transcribe
multipart/form-data file=<audio.wav>
```

## Models

Defaults:

- `FUNASR_MODEL=paraformer-zh`
- `FUNASR_VAD_MODEL=fsmn-vad`
- `FUNASR_PUNC_MODEL=ct-punc`

The first run downloads model files to the ModelScope cache.

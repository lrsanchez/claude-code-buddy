#!/usr/bin/env python3
"""
Voice handler — faster-whisper STT + OpenRouter LLM for the R1 voice creation.

Environment variables:
  OPENROUTER_KEY   required — your OpenRouter API key
  VOICE_MODEL      optional — OpenRouter model id (default: google/gemini-2.0-flash-001)
  WHISPER_MODEL    optional — faster-whisper model size (default: base)
"""

import asyncio
import logging
import os
import tempfile
from typing import Optional

log = logging.getLogger("buddy")

OPENROUTER_KEY     = os.environ.get("OPENROUTER_KEY", "")
VOICE_MODEL        = os.environ.get("VOICE_MODEL", "google/gemini-2.0-flash-001")
WHISPER_MODEL_SIZE = os.environ.get("WHISPER_MODEL", "base")
MAX_HISTORY        = 8    # message pairs kept per session
MAX_TOKENS         = 150  # keep replies short — they're spoken aloud

TTS_VOICE = os.environ.get("TTS_VOICE", "en-US-AriaNeural")

SYSTEM_PROMPT = (
    "You are a helpful voice assistant. Your replies are spoken aloud — keep them brief "
    "and conversational. No markdown, no bullet points, no numbered lists. "
    "Answer in 1-3 sentences unless more detail is explicitly asked for."
)


class VoiceHandler:

    def __init__(self):
        self._whisper  = None
        self._loading  = False
        self._sessions: dict = {}   # session_id → message list

    # ── Whisper ───────────────────────────────────────────────────────────────

    def load_whisper(self):
        """Load the Whisper model (blocking — run in executor)."""
        if self._whisper is not None or self._loading:
            return
        self._loading = True
        try:
            from faster_whisper import WhisperModel
            log.info(f"Loading Whisper model '{WHISPER_MODEL_SIZE}'…")
            self._whisper = WhisperModel(
                WHISPER_MODEL_SIZE,
                device="cpu",
                compute_type="int8",
            )
            log.info("Whisper ready")
        except ImportError:
            log.warning("faster-whisper not installed — run: pip install faster-whisper")
        except Exception as e:
            log.error(f"Whisper load failed: {e}")
        finally:
            self._loading = False

    def _transcribe_sync(self, path: str) -> str:
        if self._whisper is None:
            return ""
        try:
            segments, _ = self._whisper.transcribe(
                path,
                language="en",
                vad_filter=True,
                beam_size=1,
            )
            return " ".join(s.text.strip() for s in segments).strip()
        except Exception as e:
            log.debug(f"transcribe error: {e}")
            return ""

    # ── OpenRouter ────────────────────────────────────────────────────────────

    async def _chat(self, session_id: str, user_text: str) -> str:
        if not OPENROUTER_KEY:
            return "Please set the OPENROUTER_KEY environment variable to enable voice."

        history = self._sessions.get(session_id, [])
        history.append({"role": "user", "content": user_text})

        messages = [{"role": "system", "content": SYSTEM_PROMPT}] + history[-(MAX_HISTORY * 2):]

        try:
            import aiohttp
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    "https://openrouter.ai/api/v1/chat/completions",
                    headers={
                        "Authorization": f"Bearer {OPENROUTER_KEY}",
                        "HTTP-Referer":  "https://claude-buddy",
                        "X-Title":       "Claude Buddy Voice",
                        "Content-Type":  "application/json",
                    },
                    json={
                        "model":      VOICE_MODEL,
                        "messages":   messages,
                        "max_tokens": MAX_TOKENS,
                    },
                    timeout=aiohttp.ClientTimeout(total=30),
                ) as resp:
                    data = await resp.json()

            reply = data["choices"][0]["message"]["content"].strip()
            history.append({"role": "assistant", "content": reply})
            self._sessions[session_id] = history[-(MAX_HISTORY * 2):]
            return reply

        except Exception as e:
            log.warning(f"OpenRouter error: {e}")
            return "Sorry, I couldn't reach the model right now."

    # ── Route handler ─────────────────────────────────────────────────────────

    async def _tts(self, text: str) -> Optional[str]:
        """Generate speech via edge-tts; return base64 MP3 or None on failure."""
        try:
            import io, base64
            import edge_tts
            communicate = edge_tts.Communicate(text, TTS_VOICE)
            buf = io.BytesIO()
            async for chunk in communicate.stream():
                if chunk["type"] == "audio":
                    buf.write(chunk["data"])
            data = buf.getvalue()
            if data:
                return base64.b64encode(data).decode("utf-8")
        except ImportError:
            log.warning("edge-tts not installed — run: pip install edge-tts")
        except Exception as e:
            log.warning(f"TTS error: {e}")
        return None

    async def handle_ask(self, request) -> dict:
        """Read multipart audio field, transcribe, chat, return {ok, text, transcript}."""

        # Lazy-load Whisper on first request (blocking load runs in thread)
        if self._whisper is None and not self._loading:
            loop = asyncio.get_event_loop()
            await loop.run_in_executor(None, self.load_whisper)

        audio_bytes = b""
        session_id  = "default"

        try:
            reader = await request.multipart()
            async for field in reader:
                if field.name == "audio":
                    audio_bytes = await field.read()
                elif field.name == "session_id":
                    raw = await field.read()
                    session_id = raw.decode("utf-8", errors="replace").strip() or "default"
        except Exception as e:
            log.debug(f"multipart read error: {e}")
            return {"ok": False, "error": "failed to read audio"}

        if not audio_bytes:
            return {"ok": False, "error": "no audio received"}

        # Write to temp file, transcribe in thread, clean up
        tmp = tempfile.NamedTemporaryFile(suffix=".webm", delete=False)
        tmp.write(audio_bytes)
        tmp.close()

        loop = asyncio.get_event_loop()
        transcript = await loop.run_in_executor(None, self._transcribe_sync, tmp.name)

        import os as _os
        _os.unlink(tmp.name)

        if not transcript:
            return {"ok": False, "error": "no speech detected", "transcript": ""}

        log.info(f"Voice [{session_id[:8]}]: \"{transcript[:80]}\"")
        reply = await self._chat(session_id, transcript)
        result: dict = {"ok": True, "text": reply, "transcript": transcript}
        audio = await self._tts(reply)
        if audio:
            result["audio"] = audio
        return result

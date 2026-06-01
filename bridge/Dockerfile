FROM python:3.11-slim

WORKDIR /app

# ffmpeg is required by faster-whisper to decode WebM/Opus audio from the browser
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg \
 && rm -rf /var/lib/apt/lists/*

# Install Python dependencies
COPY requirements-voice.txt .
RUN pip install --no-cache-dir -r requirements-voice.txt

# Copy only the voice server files — no daemon, no BLE, no hooks
COPY voice_server.py voice_handler.py r1-voice.html ./

# Port (override with VOICE_HTTP_PORT env var if needed)
ENV VOICE_HTTP_PORT=3200
EXPOSE 3200

CMD ["python3", "voice_server.py"]

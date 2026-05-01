FROM python:3.11-slim

# ffmpeg for M4A/AAC encode/decode (pydub), libsndfile for soundfile
RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg libsndfile1 && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

ENV PYTHONUNBUFFERED=1

# Cloud Run sends SIGTERM on scale-down; main.py handles it gracefully.
CMD ["python", "main.py"]

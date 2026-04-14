# ─────────────────────────────────────────────────────────────────────────────
# Resulyzer — Python ML Service Dockerfile
# ─────────────────────────────────────────────────────────────────────────────
FROM python:3.11-slim

# Install system dependencies:
#   libpoppler-cpp-dev — needed by pdfplumber to parse PDFs
#   dos2unix           — converts CRLF → LF in entrypoint.sh (safe on any OS)
RUN apt-get update && apt-get install -y --no-install-recommends \
    libpoppler-cpp-dev \
    dos2unix \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy requirements first (Docker layer-cache trick — pip only re-runs if this changes)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy all source code
COPY . .

# Guarantee LF line endings in the shell script regardless of the host OS.
# This prevents "bad interpreter: /bin/sh^M" errors on Linux containers
# when the file was edited on Windows (which uses CRLF).
RUN dos2unix entrypoint.sh && chmod +x entrypoint.sh

EXPOSE 5000

# The entrypoint script trains the model (if needed) and then starts gunicorn.
# Training data is mounted at /data by docker-compose at RUNTIME, not build time.
ENTRYPOINT ["./entrypoint.sh"]

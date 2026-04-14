#!/bin/sh
# entrypoint.sh â€” runs inside the Python container when it starts
# 1. Train the model if model.pkl does not exist yet
# 2. Start gunicorn

set -e

MODEL="/app/model.pkl"
DATA="/data/resumes.csv"

if [ ! -f "$MODEL" ]; then
    if [ -f "$DATA" ]; then
        echo "[entrypoint] model.pkl not found â€” training now from $DATA..."
        python train_model.py
    else
        echo "[entrypoint] ERROR: model.pkl missing AND no training data at $DATA"
        echo "[entrypoint] Mount the data/ directory or pre-train the model."
        exit 1
    fi
else
    echo "[entrypoint] model.pkl found â€” skipping training."
fi

echo "[entrypoint] Starting gunicorn..."
exec gunicorn \
    --workers 2 \
    --bind 0.0.0.0:5000 \
    --timeout 120 \
    --access-logfile - \
    --error-logfile - \
    analyze_resume:app

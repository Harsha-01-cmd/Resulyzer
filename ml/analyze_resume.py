"""
analyze_resume.py — Resulyzer ML Service
=========================================
Flask web application that exposes:
  GET  /           → Web UI (upload page)
  GET  /health     → Health-check (used by Docker)
  GET  /roles      → List of available roles from the database
  POST /analyze    → Single-file analysis (role prediction + confidence)
  POST /analyze-full → Multi-file analysis with skill matching + score

Design decisions:
  • DB connections use a retry helper (Docker MySQL starts slowly).
  • Skill matching uses regex word-boundary matching to avoid
    false positives like "java" matching "javascript".
  • Text is pre-processed consistently in one place.
  • All temporary files are cleaned up in finally blocks.
"""

import os
import re
import pickle
import logging
import tempfile
import time

import mysql.connector
from flask import Flask, request, jsonify, render_template, send_from_directory

from extract_text import extract_text

# ── Config ─────────────────────────────────────────────────────────────────────
BASE_DIR   = os.path.dirname(os.path.abspath(__file__))
MODEL_PATH = os.path.join(BASE_DIR, "model.pkl")

DB_HOST     = os.environ.get("DB_HOST",     "127.0.0.1")
DB_PORT     = int(os.environ.get("DB_PORT", "3306"))
DB_USER     = os.environ.get("DB_USER",     "appuser")
DB_PASSWORD = os.environ.get("DB_PASSWORD", "apppass")
DB_NAME     = os.environ.get("DB_NAME",     "resume_analyzer")

ALLOWED_EXTENSIONS = {".pdf", ".docx", ".txt"}

# ── Logging ────────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
)
logger = logging.getLogger(__name__)

# ── Load ML model ──────────────────────────────────────────────────────────────
# The model.pkl is a tuple (TfidfVectorizer, LogisticRegression).
# It is created by train_model.py and baked into the Docker image.
try:
    with open(MODEL_PATH, "rb") as fh:
        vectorizer, model = pickle.load(fh)
    logger.info("ML model loaded from '%s'", MODEL_PATH)
except FileNotFoundError:
    logger.critical("model.pkl not found at '%s'. Run train_model.py first.", MODEL_PATH)
    raise SystemExit(1)
except Exception as exc:
    logger.critical("Failed to load model: %s", exc)
    raise SystemExit(1)


# ── Database helpers ───────────────────────────────────────────────────────────

def _get_db_connection(retries: int = 5, delay: int = 3):
    """
    Open a MySQL connection with retry logic.
    MySQL container may take up to 30 s to fully start, so we wait.
    """
    for attempt in range(1, retries + 1):
        try:
            conn = mysql.connector.connect(
                host=DB_HOST, port=DB_PORT,
                user=DB_USER, password=DB_PASSWORD,
                database=DB_NAME, connect_timeout=5,
            )
            return conn
        except mysql.connector.Error as exc:
            logger.warning("DB attempt %d/%d failed: %s", attempt, retries, exc)
            if attempt < retries:
                time.sleep(delay)
    logger.error("All DB connection attempts exhausted.")
    return None


def get_roles_from_db() -> list[str]:
    """Return a sorted list of distinct roles stored in role_skills."""
    conn = _get_db_connection()
    if conn is None:
        return []
    try:
        cur = conn.cursor()
        cur.execute("SELECT DISTINCT role FROM role_skills ORDER BY role")
        return [row[0] for row in cur.fetchall()]
    except Exception as exc:
        logger.error("Could not fetch roles: %s", exc)
        return []
    finally:
        conn.close()


def get_skills_for_role(role: str) -> dict[str, float]:
    """Return {skill: weight} for the given role from the database."""
    conn = _get_db_connection()
    if conn is None:
        return {}
    try:
        cur = conn.cursor()
        cur.execute(
            "SELECT skill, weight FROM role_skills WHERE role = %s", (role,)
        )
        return {row[0]: float(row[1]) for row in cur.fetchall()}
    except Exception as exc:
        logger.error("Could not fetch skills for role '%s': %s", role, exc)
        return {}
    finally:
        conn.close()


# ── Text pre-processing ────────────────────────────────────────────────────────

def preprocess(text: str) -> str:
    """
    Lower-case and normalise whitespace/punctuation.
    Must match the pre-processing used in train_model.py.
    """
    text = text.lower()
    text = re.sub(r"[^a-z0-9 ]", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def skill_present(skill: str, text: str) -> bool:
    """
    Check whether a skill keyword appears in the resume text using
    whole-word matching to avoid false positives.

    Examples:
      skill_present("java", "javascript developer")  → False
      skill_present("java", "java spring developer") → True
      skill_present("sql",  "he knows mysql well")   → True  (short acronyms allowed)
    """
    # For skills that are short acronyms (≤3 chars), substring match is fine
    if len(skill) <= 3:
        return skill in text

    # For multi-word skills like "spring boot" or "rest api", use substring match
    # (the words are already separated by spaces in the cleaned text)
    if " " in skill:
        return skill in text

    # Single long word: use word boundary (\b) in regex
    pattern = r"\b" + re.escape(skill) + r"\b"
    return bool(re.search(pattern, text))


# ── Flask app ──────────────────────────────────────────────────────────────────
app = Flask(__name__, static_folder="static", template_folder="templates")


# ─── Web UI ───────────────────────────────────────────────────────────────────
@app.route("/")
def index():
    """Serve the single-page web interface."""
    return render_template("index.html")


@app.route("/static/<path:filename>")
def static_files(filename):
    return send_from_directory(app.static_folder, filename)


# ─── API endpoints ─────────────────────────────────────────────────────────────

@app.route("/health", methods=["GET"])
def health():
    """Docker health-check endpoint. Returns 200 OK when the service is ready."""
    return jsonify({"status": "ok"}), 200


@app.route("/roles", methods=["GET"])
def roles():
    """List of job roles available in the database."""
    return jsonify({"roles": get_roles_from_db()})


@app.route("/analyze", methods=["POST"])
def analyze():
    """
    Single-resume endpoint.

    Request (multipart/form-data):
        file  — the resume file (.pdf, .docx, or .txt)

    Response (JSON):
        predicted_role  — string
        confidence      — {role: percentage, ...}
        resume_text     — cleaned text excerpt (first 500 chars)
    """
    if "file" not in request.files:
        return jsonify({"error": "No file uploaded. Use key 'file'."}), 400

    uploaded = request.files["file"]
    if not uploaded.filename:
        return jsonify({"error": "Empty filename."}), 400

    _, ext = os.path.splitext(uploaded.filename)
    if ext.lower() not in ALLOWED_EXTENSIONS:
        return jsonify({"error": f"Unsupported format '{ext}'. Use: {ALLOWED_EXTENSIONS}"}), 400

    tmp_path = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=ext) as tmp:
            uploaded.save(tmp)
            tmp_path = tmp.name

        raw_text = extract_text(tmp_path)
        if not raw_text or not raw_text.strip():
            return jsonify({"error": "Could not extract any text from the file."}), 422

        cleaned  = preprocess(raw_text)
        X        = vectorizer.transform([cleaned])
        predicted_role = model.predict(X)[0]

        probabilities = model.predict_proba(X)[0]
        confidence = {
            role_name: round(float(prob) * 100, 1)
            for role_name, prob in zip(model.classes_, probabilities)
        }

        return jsonify({
            "predicted_role": predicted_role,
            "confidence":     confidence,
            "resume_text":    cleaned[:500],   # send an excerpt for display
        })
    except Exception:
        logger.exception("Error analysing single resume '%s'", uploaded.filename)
        return jsonify({"error": "Internal server error."}), 500
    finally:
        if tmp_path and os.path.exists(tmp_path):
            os.unlink(tmp_path)


@app.route("/analyze-full", methods=["POST"])
def analyze_full():
    """
    Batch resume endpoint — handles multiple files at once.

    Request (multipart/form-data):
        files[]  — one or more resume files
        role     — (optional) target job role for skill-matching

    Response (JSON):
        {
          "results": [
            {
              "filename":       string,
              "predicted_role": string,
              "confidence":     {role: %, ...},
              "selection_score": int (0-100) | null,
              "matched_skills": [...],
              "missing_skills": [...]
            },
            ...
          ]
        }
    """
    files = request.files.getlist("files")
    if not files or all(f.filename == "" for f in files):
        return jsonify({"error": "No files uploaded."})
    
    results: list[dict] = []

    for uploaded in files:
        if not uploaded.filename:
            continue

        _, ext = os.path.splitext(uploaded.filename)
        if ext.lower() not in ALLOWED_EXTENSIONS:
            results.append({"filename": uploaded.filename, "error": f"Unsupported format '{ext}'."})
            continue

        tmp_path = None
        try:
            with tempfile.NamedTemporaryFile(delete=False, suffix=ext) as tmp:
                uploaded.save(tmp)
                tmp_path = tmp.name

            raw_text = extract_text(tmp_path)
            if not raw_text or not raw_text.strip():
                results.append({"filename": uploaded.filename, "error": "No text could be extracted."})
                continue

            cleaned        = preprocess(raw_text)
            X              = vectorizer.transform([cleaned])
            predicted_role = model.predict(X)[0]

            probabilities = model.predict_proba(X)[0]
            confidence = {
                role_name: round(float(prob) * 100, 1)
                for role_name, prob in zip(model.classes_, probabilities)
            }

            # ── Skill matching ─────────────────────────────────────────────
            matched_skills: list[str] = []
            missing_skills: list[str] = []
            selection_score = None
            
            role_skills = get_skills_for_role(predicted_role)

            if role_skills:
                total_weight   = 0.0
                matched_weight = 0.0

                for skill, weight in role_skills.items():
                    total_weight += weight
                    if skill_present(skill, cleaned):
                        matched_weight += weight
                        matched_skills.append(skill)
                    else:
                        missing_skills.append(skill)

                selection_score = (
                    int((matched_weight / total_weight) * 100) if total_weight > 0 else 0
                )

            results.append({
                "filename":       uploaded.filename,
                "predicted_role": predicted_role,
                "confidence":     confidence,
                "selection_score": selection_score,
                "matched_skills":  matched_skills,
                "missing_skills":  missing_skills,
            })

        except Exception:
            logger.exception("Error processing '%s'", uploaded.filename)
            results.append({"filename": uploaded.filename, "error": "Processing failed."})
        finally:
            if tmp_path and os.path.exists(tmp_path):
                os.unlink(tmp_path)

    return jsonify({"results": results})


# ── Entry point ────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    debug = os.environ.get("FLASK_DEBUG", "false").lower() == "true"
    app.run(host="0.0.0.0", port=port, debug=debug)

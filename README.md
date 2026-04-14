# Resulyzer — AI Resume Analyzer

> An AI-powered resume screening system that predicts job roles, matches skills, and generates selection scores. Built with Python (Flask + ML), Java (CLI), MySQL, and Docker.

## Quick Start

```bash
# 1. Navigate to the project
cd Resulyzer

# 2. Build all Docker images (first run takes 2-5 min — downloads dependencies)
docker-compose build

# 3. Start MySQL + Web UI
docker-compose up mysql python-ml

# 4. Open your browser → http://localhost:5000
#    Upload resumes directly through the web UI — no folder copying needed!

# 5. Stop everything
docker-compose down
```

> **First run note:** MySQL takes ~30 seconds to initialise. The roles dropdown will show ⏳ and auto-populate once the database is ready.

## Upload Resumes via the Web UI

1. Go to **http://localhost:5000**
2. **Drag & drop** your PDF, DOCX, or TXT resume(s) onto the upload zone — or click to browse

4. Click **Analyze Resumes**
5. Results appear instantly — predicted role, confidence %, matched & missing skills, and a 0–100 selection score

> ✅ Resumes are uploaded directly through the browser. You do **not** need to put files in any folder.

## Use the Java CLI (Optional)

The Java CLI is an optional command-line tool that calls the Python ML API. Run it with:

```bash
docker-compose run --rm java-cli \
  java -jar resulyzer.jar /path/to/resume.pdf "Backend"
```

Available roles: `Backend`, `Frontend`, `Data Analyst`, `DevOps`, `Full Stack`, `ML Engineer`

## Features

- 📄 Upload PDF, DOCX, or TXT resumes via drag-and-drop in the browser
- 🤖 AI predicts the most likely job role (with confidence %)
- 🎯 Compare resume against predicted role for missing skills
- ✅ Shows missing skills for the predicted role with colour-coded chips
- 📊 Selection score 0–100%
- 🌐 Beautiful dark glassmorphism web UI
- ⌨️ Java CLI for command-line usage (optional)

## Tech Stack

| Layer | Technology |
|-------|------------|
| ML | scikit-learn (TF-IDF + Logistic Regression) |
| API | Python 3.11 + Flask + Gunicorn |
| CLI | Java 17 (JDBC) |
| Database | MySQL 8.0 |
| Containers | Docker + Docker Compose |

## File Structure

```
Resulyzer/
├── data/
│   ├── resumes.csv         ← ML training data (text + role columns)
│   └── role_skills.sql     ← DB seed (roles + weighted skills)
├── ml/                     ← Python ML service
│   ├── analyze_resume.py   ← Flask app + all API endpoints
│   ├── extract_text.py     ← PDF / DOCX / TXT text extraction
│   ├── train_model.py      ← TF-IDF + Logistic Regression trainer
│   ├── entrypoint.sh       ← Trains model if needed, then starts gunicorn
│   ├── templates/index.html← Web UI (drag-and-drop upload)
│   ├── static/css/         ← Glassmorphism stylesheet
│   ├── static/js/app.js    ← Frontend logic (upload, results rendering)
│   └── Dockerfile
├── java-cli/               ← Optional Java CLI
│   ├── src/main/java/com/resulyzer/
│   │   ├── Main.java       ← CLI entry point
│   │   ├── DBUtil.java     ← JDBC connection helper
│   │   └── RoleSkillDAO.java← Fetches role skills from MySQL
│   ├── .dockerignore       ← Excludes large zip from build context
│   └── Dockerfile
└── docker-compose.yml      ← Orchestrates MySQL + Python ML + Java CLI
```

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Roles dropdown shows ⏳ for a long time | MySQL is still initialising — wait ~40s, it will auto-populate |
| `docker-compose build` fails on Java | Needs internet access to download MySQL JDBC driver from Maven Central |
| Port 5000 already in use | Change `"5000:5000"` to `"5001:5000"` in docker-compose.yml |
| Port 3307 already in use | Change `"3307:3306"` to `"3308:3306"` in docker-compose.yml |
| model.pkl missing error | Make sure `./data` is mounted — docker-compose does this automatically |

## Read the Full Learning Documentation

See `resulyzer_learning_docs.md` for:
- Line-by-line code explanation of every file
- Docker deep dive (images, containers, volumes, networking)
- ML concepts (TF-IDF, Logistic Regression)
- Common errors and fixes
- How to add new roles

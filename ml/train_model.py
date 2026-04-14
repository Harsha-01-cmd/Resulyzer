"""
train_model.py
--------------
Trains a TF-IDF + Logistic Regression resume classifier and saves model.pkl.

Run this script ONCE (or whenever the training data changes):
    python train_model.py

Inside Docker it is automatically triggered at container build time via the Dockerfile.
"""

import os
import re
import pickle
import logging

import pandas as pd
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import cross_val_score, train_test_split
from sklearn.metrics import classification_report

# ── Logging setup ──────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger(__name__)

# ── Paths ──────────────────────────────────────────────────────────────────────
BASE_DIR   = os.path.dirname(os.path.abspath(__file__))
MODEL_PATH = os.path.join(BASE_DIR, "model.pkl")

# Docker mounts training data at /data; locally it sits at ../data
_docker_path = "/data/resumes.csv"
_local_path  = os.path.join(BASE_DIR, "..", "data", "resumes.csv")
DATA_PATH = _docker_path if os.path.isfile(_docker_path) else _local_path


# ── Text pre-processor ─────────────────────────────────────────────────────────
def preprocess(text: str) -> str:
    """Lowercase, strip punctuation, collapse whitespace."""
    text = text.lower()
    text = re.sub(r"[^a-z0-9 ]", " ", text)   # keep letters, digits, spaces
    text = re.sub(r"\s+", " ", text).strip()
    return text


# ── Training pipeline ──────────────────────────────────────────────────────────
def train() -> None:
    # 1. Load ──────────────────────────────────────────────────────────────────
    if not os.path.isfile(DATA_PATH):
        logger.error("Training data not found at '%s'. Create data/resumes.csv first.", DATA_PATH)
        raise SystemExit(1)

    df = pd.read_csv(DATA_PATH)

    required_cols = {"text", "role"}
    if not required_cols.issubset(df.columns):
        logger.error("CSV must contain columns: %s. Got: %s", required_cols, set(df.columns))
        raise SystemExit(1)

    df.dropna(subset=["text", "role"], inplace=True)

    if len(df) < 10:
        logger.warning("Very small dataset (%d rows). Model accuracy will be low.", len(df))

    n_roles = df["role"].nunique()
    logger.info("Loaded %d samples across %d roles: %s", len(df), n_roles, df["role"].unique().tolist())

    # 2. Pre-process ───────────────────────────────────────────────────────────
    df["clean_text"] = df["text"].apply(preprocess)

    # 3. Vectorise ─────────────────────────────────────────────────────────────
    vectorizer = TfidfVectorizer(
        stop_words="english",
        ngram_range=(1, 2),     # unigrams + bigrams catch "spring boot", "rest api"
        max_features=15_000,    # cap vocabulary to avoid overfitting on tiny data
        sublinear_tf=True,      # log-scale TF damping
    )
    X = vectorizer.fit_transform(df["clean_text"])
    y = df["role"]

    logger.info("Vocabulary size: %d features", len(vectorizer.vocabulary_))

    # 4. Train ─────────────────────────────────────────────────────────────────
    model = LogisticRegression(max_iter=1000, C=1.0, solver="lbfgs")
    model.fit(X, y)

    # 5. Evaluate ──────────────────────────────────────────────────────────────
    if len(df) >= 20:
        # Cross-validation gives an unbiased accuracy estimate
        cv_folds = min(5, len(df) // n_roles)
        scores = cross_val_score(model, X, y, cv=cv_folds, scoring="accuracy")
        logger.info(
            "Cross-validation accuracy: %.1f%% ± %.1f%%",
            scores.mean() * 100,
            scores.std() * 100,
        )
    else:
        logger.info("Skipping cross-validation (not enough data).")

    # Train/test split report (informational only, not used to select the model)
    if len(df) >= 10:
        X_train, X_test, y_train, y_test = train_test_split(
            X, y, test_size=0.2, random_state=42, stratify=y
        )
        model_eval = LogisticRegression(max_iter=1000, C=1.0, solver="lbfgs")
        model_eval.fit(X_train, y_train)
        y_pred = model_eval.predict(X_test)
        logger.info("Hold-out evaluation:\n%s", classification_report(y_test, y_pred))

    # 6. Persist ───────────────────────────────────────────────────────────────
    # Save (vectorizer, model) together so the Flask app loads both in one step.
    with open(MODEL_PATH, "wb") as fh:
        pickle.dump((vectorizer, model), fh, protocol=pickle.HIGHEST_PROTOCOL)

    logger.info("Model saved to '%s'", MODEL_PATH)


if __name__ == "__main__":
    train()

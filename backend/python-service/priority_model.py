"""
Feature extraction + priority prediction.

In production this loads a trained model (e.g. a gradient-boosted
classifier via scikit-learn/XGBoost, versioned in a model registry).
Here we show a clean interface plus a transparent rules+heuristic
fallback so the module is runnable and testable standalone.
"""

from datetime import datetime, timezone
from typing import Optional, Tuple

URGENT_KEYWORDS = {"urgent", "asap", "critical", "outage", "blocker", "security"}

PRIORITY_LEVELS = ["LOW", "MEDIUM", "HIGH", "CRITICAL"]


def _hours_until_due(due_date: Optional[str]) -> Optional[float]:
    if not due_date:
        return None
    try:
        due = datetime.fromisoformat(due_date.replace("Z", "+00:00"))
        delta = due - datetime.now(timezone.utc)
        return delta.total_seconds() / 3600
    except ValueError:
        return None


def extract_features(title: str, description: str, due_date: Optional[str]) -> dict:
    text = f"{title} {description}".lower()
    return {
        "hours_until_due": _hours_until_due(due_date),
        "has_urgent_keyword": any(k in text for k in URGENT_KEYWORDS),
        "title_length": len(title or ""),
    }


def predict_priority(title: str, description: str, due_date: Optional[str]) -> Tuple[str, float]:
    """
    Returns (priority_label, confidence[0..1]).

    Replace this body with `model.predict_proba(features)` once a
    trained classifier is available; keep the same signature so the
    Kafka consumer in main.py doesn't need to change.
    """
    features = extract_features(title, description, due_date)
    hours = features["hours_until_due"]
    urgent = features["has_urgent_keyword"]

    if urgent and hours is not None and hours < 24:
        return "CRITICAL", 0.93
    if urgent or (hours is not None and hours < 24):
        return "HIGH", 0.82
    if hours is not None and hours < 72:
        return "MEDIUM", 0.7
    return "LOW", 0.6

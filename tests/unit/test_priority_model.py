from datetime import datetime, timedelta, timezone
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "python-service"))

from priority_model import predict_priority


def _due_in(hours):
    return (datetime.now(timezone.utc) + timedelta(hours=hours)).isoformat()


def test_urgent_keyword_and_close_due_date_is_critical():
    priority, confidence = predict_priority("URGENT outage", "prod is down", _due_in(2))
    assert priority == "CRITICAL"
    assert confidence > 0.9


def test_far_due_date_no_keywords_is_low():
    priority, _ = predict_priority("Update docs", "minor cleanup", _due_in(24 * 30))
    assert priority == "LOW"


def test_no_due_date_no_keywords_defaults_low():
    priority, _ = predict_priority("Refactor module", "", None)
    assert priority == "LOW"

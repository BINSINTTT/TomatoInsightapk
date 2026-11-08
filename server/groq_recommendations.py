"""
Call Groq Chat Completions API using disease bar scores from the web app.
Set GROQ_API_KEY in server/.env (see server/.env.example).
"""

from __future__ import annotations

import json
import os
import re
from typing import Any

import requests

GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions"
# Llama 3.1 IDs are deprecated/unavailable on many accounts; list models: GET /openai/v1/models
DEFAULT_MODEL = "openai/gpt-oss-20b"

BAR_KEYS = ("healthy", "tlcv", "early_blight", "spotted_wilt")

DISPLAY_NAMES = {
    "healthy": "Healthy",
    "tlcv": "Yellow leaf curl virus",
    "early_blight": "Early blight",
    "spotted_wilt": "Spotted wilt",
}

DISEASE_FACTS: dict[str, dict[str, Any]] = {
    "early_blight": {
        "summary": (
            "Early blight (Alternaria) causes brown spots with rings on lower leaves "
            "and can defoliate plants."
        ),
        "action_steps": [
            "Remove and destroy infected leaves; do not compost them.",
            "Improve airflow by spacing plants and pruning lower foliage.",
            "Avoid overhead watering; water at the base in the morning.",
            "Apply a copper-based or chlorothalonil fungicide per label directions.",
            "Rotate tomatoes to a new area next season; do not replant in the same spot.",
        ],
        "prevention_tips": [
            "Mulch around plants to reduce soil splash onto leaves.",
            "Inspect lower leaves weekly during humid weather.",
        ],
    },
    "spotted_wilt": {
        "summary": (
            "Tomato spotted wilt virus (TSWV) is spread by thrips and causes bronze spots, "
            "rings, and stunted growth."
        ),
        "action_steps": [
            "Remove and destroy infected plants immediately to limit spread.",
            "Control thrips with insecticidal soap or approved products; focus on undersides of leaves.",
            "Use reflective mulch early in the season to repel thrips.",
            "Remove weeds near the farm that host thrips and the virus.",
            "Do not replant tomatoes in the same bed without thrips management.",
        ],
        "prevention_tips": [
            "Use TSWV-resistant tomato varieties when available.",
            "Install fine mesh row covers until plants flower, if practical.",
        ],
    },
    "tlcv": {
        "summary": (
            "Tomato yellow leaf curl virus (TYLCV) causes upward curling, yellowing, and reduced "
            "fruit set; whiteflies spread it."
        ),
        "action_steps": [
            "Remove severely infected plants to protect nearby healthy ones.",
            "Control whiteflies with yellow sticky traps and approved insecticides.",
            "Spray undersides of leaves where whiteflies feed; repeat as directed.",
            "Remove solanaceous weeds (tomato relatives) around the field.",
            "Avoid bringing new plants from unknown sources into the farm.",
        ],
        "prevention_tips": [
            "Plant TYLCV-tolerant varieties where the virus is common.",
            "Inspect new growth twice weekly for curling and whitefly activity.",
        ],
    },
    "healthy": {
        "summary": "This leaf looks healthy. Continue good practices to keep plants productive.",
        "action_steps": [
            "Maintain even watering; avoid long dry periods followed by heavy soaking.",
            "Feed with balanced fertilizer according to plant stage.",
            "Stake or trellis plants to keep foliage off the soil.",
            "Scan other tiles regularly so problems are caught early.",
        ],
        "prevention_tips": [
            "Remove yellowing lower leaves to improve airflow.",
            "Sanitize tools if you work on a diseased plant elsewhere.",
        ],
    },
}


def groq_is_configured() -> bool:
    key = os.environ.get("GROQ_API_KEY", "").strip()
    return bool(key and key != "your_groq_api_key_here")


def _clamp_pct(value: Any) -> float:
    """One decimal, matching the precision the Ultralytics response reports."""
    try:
        n = round(float(value), 1)
    except (TypeError, ValueError):
        return 0.0
    return max(0.0, min(100.0, n))


def _format_pct(value: float) -> str:
    return str(int(value)) if float(value).is_integer() else f"{value:.1f}"


def _normalize_scores(raw: dict[str, Any] | None) -> dict[str, float]:
    scores = {key: 0.0 for key in BAR_KEYS}
    if not raw:
        return scores
    for key in BAR_KEYS:
        scores[key] = _clamp_pct(raw.get(key))
    return scores


def _detected_matches(scores: dict[str, float]) -> list[dict[str, Any]]:
    """Every bar above 0%, highest first — each gets its own care guide."""
    matches = [
        {"bar_key": key, "pct": scores[key]}
        for key in BAR_KEYS
        if scores[key] > 0
    ]
    matches.sort(key=lambda item: (-item["pct"], BAR_KEYS.index(item["bar_key"])))
    return matches


def _find_top(scores: dict[str, float]) -> dict[str, Any] | None:
    matches = _detected_matches(scores)
    if not matches:
        return None

    top = matches[0]
    second = matches[1] if len(matches) > 1 else None
    second_key = second["bar_key"] if second else None
    second_pct = second["pct"] if second else 0.0

    return {
        "bar_key": top["bar_key"],
        "pct": top["pct"],
        "second_bar_key": second_key,
        "second_pct": second_pct,
        "margin": round(top["pct"] - second_pct, 1),
        "detected": matches,
    }


def _severity_label(bar_key: str, pct: float) -> str:
    if bar_key == "healthy":
        return "Healthy"
    if pct >= 67:
        return "Severe"
    if pct >= 34:
        return "Moderate"
    return "Mild"


def _build_user_message(scores: dict[str, float], top: dict[str, Any]) -> str:
    bar_key = top["bar_key"]
    pct = top["pct"]
    facts = DISEASE_FACTS.get(bar_key, DISEASE_FACTS["healthy"])
    severity = _severity_label(bar_key, pct)

    payload = {
        "scores_percent": scores,
        "top_match": {
            "bar_key": bar_key,
            "disease_name": DISPLAY_NAMES.get(bar_key, bar_key),
            "confidence_percent": pct,
            "severity_band": severity,
            "second_place": {
                "bar_key": top["second_bar_key"],
                "percent": top["second_pct"],
                "margin_over_second": top["margin"],
            },
        },
        "trusted_facts": facts,
        "rules": [
            "Use trusted_facts as the source of truth; do not invent new chemicals, doses, or products.",
            "If margin_over_second is under 15 and top is not healthy, mention uncertainty and suggest rescanning.",
            "If confidence_percent is 100 and top is not healthy, set isCriticalRemoval true and prioritize immediate leaf removal.",
            "Keep actionSteps ordered and practical for a smallholder tomato farm.",
        ],
    }
    return json.dumps(payload, indent=2)


SYSTEM_PROMPT = """You are an advisory assistant for Tomato Insight, a tomato farm leaf-scanning app.
You receive JSON with model confidence scores and trusted_facts (curated agronomy text).
Respond with ONLY valid JSON (no markdown fences) matching this schema:
{
  "diseaseName": string,
  "urgencyNote": string,
  "summary": string,
  "actionSteps": string[],
  "preventionTips": string[],
  "isCriticalRemoval": boolean
}
Do not contradict trusted_facts; you may rephrase and prioritize steps based on severity_band and scores.
Do not mention extension services or extension offices."""


def _extract_json_object(text: str) -> dict[str, Any]:
    cleaned = text.strip()
    fence = re.search(r"```(?:json)?\s*([\s\S]*?)```", cleaned)
    if fence:
        cleaned = fence.group(1).strip()
    return json.loads(cleaned)


def _normalize_guide(data: dict[str, Any], bar_key: str, pct: float) -> dict[str, Any]:
    facts = DISEASE_FACTS.get(bar_key, DISEASE_FACTS["healthy"])
    disease_name = str(data.get("diseaseName") or DISPLAY_NAMES.get(bar_key, bar_key)).strip()

    action_steps = data.get("actionSteps") or data.get("action_steps")
    if not isinstance(action_steps, list) or not action_steps:
        action_steps = list(facts["action_steps"])
    else:
        action_steps = [str(s).strip() for s in action_steps if str(s).strip()]

    prevention = data.get("preventionTips") or data.get("prevention_tips")
    if not isinstance(prevention, list):
        prevention = list(facts.get("prevention_tips") or [])
    else:
        prevention = [str(s).strip() for s in prevention if str(s).strip()]

    is_critical = bool(data.get("isCriticalRemoval"))
    if bar_key != "healthy" and pct >= 100:
        is_critical = True

    return {
        "diseaseName": disease_name,
        "urgencyNote": str(data.get("urgencyNote") or "").strip()
        or "Guidance only — verify on the plant before treating.",
        "summary": str(data.get("summary") or facts["summary"]).strip(),
        "actionSteps": action_steps,
        "preventionTips": prevention,
        "isCriticalRemoval": is_critical,
        "source": "groq",
    }


def _fallback_guide(bar_key: str, pct: float) -> dict[str, Any]:
    facts = DISEASE_FACTS.get(bar_key, DISEASE_FACTS["healthy"])
    is_healthy = bar_key == "healthy"
    severity = _severity_label(bar_key, pct)
    action_steps = list(facts["action_steps"])
    is_critical = not is_healthy and pct >= 100

    if is_healthy:
        urgency = "Keep up regular monitoring to catch problems early."
    elif is_critical:
        urgency = (
            "Critical (100%): remove this leaf immediately and destroy it away from the farm. "
            "Do not compost."
        )
        action_steps = [
            "Remove this leaf immediately; bag it and dispose of it away from healthy plants (do not compost).",
            *action_steps,
        ]
    elif severity == "Severe":
        urgency = f"Urgent ({_format_pct(pct)}%): start treatment within 24–48 hours."
    elif severity == "Moderate":
        urgency = f"Moderate ({_format_pct(pct)}%): treat this week before it spreads."
    else:
        urgency = f"Mild ({_format_pct(pct)}%): monitor closely and treat if symptoms worsen."

    return {
        "diseaseName": DISPLAY_NAMES.get(bar_key, bar_key),
        "urgencyNote": urgency,
        "summary": facts["summary"],
        "actionSteps": action_steps,
        "preventionTips": list(facts.get("prevention_tips") or []),
        "isCriticalRemoval": is_critical,
        "source": "rules",
    }


def _guides_for_matches(matches: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Built-in care text for every detected condition (healthy and diseases)."""
    return [_fallback_guide(match["bar_key"], match["pct"]) for match in matches]


def _with_guides(
    *,
    top: dict[str, Any],
    scores: dict[str, float],
    guides: list[dict[str, Any]],
    groq_used: bool,
    model: str | None = None,
    error: str | None = None,
    detail: str | None = None,
    message: str | None = None,
    http_status: int | None = None,
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "ok": True,
        "top": {"barKey": top["bar_key"], "pct": top["pct"]},
        "scores": scores,
        "guide": guides[0] if guides else None,
        "guides": guides,
        "detected": top.get("detected") or [],
        "groq_used": groq_used,
    }
    if model:
        payload["model"] = model
    if error:
        payload["error"] = error
    if detail:
        payload["detail"] = detail
    if message:
        payload["message"] = message
    if http_status is not None:
        payload["http_status"] = http_status
    return payload


def fetch_care_recommendation(scores_raw: dict[str, Any] | None) -> dict[str, Any]:
    """
    Returns { ok, guide?, guides?, error?, detail?, top? }.
    `guides` covers every score above 0% (healthy and each disease).
    `guide` stays the top match for older clients.
    On Groq failure, ok may still be true with rule-based guides.
    """
    scores = _normalize_scores(scores_raw)
    top = _find_top(scores)
    if not top:
        return {"ok": False, "error": "empty_scores", "message": "No classification scores provided."}

    bar_key = top["bar_key"]
    pct = top["pct"]
    matches = top.get("detected") or _detected_matches(scores)
    guides = _guides_for_matches(matches)

    if not groq_is_configured():
        return _with_guides(
            top=top,
            scores=scores,
            guides=guides,
            groq_used=False,
            message="GROQ_API_KEY not set; returned rule-based guides for all detections.",
        )

    api_key = os.environ.get("GROQ_API_KEY", "").strip()
    model = os.environ.get("GROQ_MODEL", DEFAULT_MODEL).strip() or DEFAULT_MODEL

    body = {
        "model": model,
        "temperature": 0.25,
        "max_tokens": 900,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": _build_user_message(scores, top)},
        ],
    }

    try:
        response = requests.post(
            GROQ_API_URL,
            json=body,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            },
            timeout=45,
        )
    except requests.RequestException as exc:
        return _with_guides(
            top=top,
            scores=scores,
            guides=guides,
            groq_used=False,
            error="upstream",
            detail=str(exc),
        )

    if response.status_code != 200:
        detail = response.text[:500]
        try:
            err = response.json().get("error", detail)
            if isinstance(err, dict):
                detail = err.get("message") or json.dumps(err)
            elif err:
                detail = str(err)
        except ValueError:
            pass
        return _with_guides(
            top=top,
            scores=scores,
            guides=guides,
            groq_used=False,
            error="groq_http",
            detail=detail,
            http_status=response.status_code,
        )

    try:
        payload = response.json()
        content = payload["choices"][0]["message"]["content"]
        parsed = _extract_json_object(content)
        primary = _normalize_guide(parsed, bar_key, pct)
        # Keep Groq wording for the top match; keep rule guides for other detections.
        guides = [primary] + guides[1:]
    except (KeyError, IndexError, json.JSONDecodeError, TypeError) as exc:
        return _with_guides(
            top=top,
            scores=scores,
            guides=guides,
            groq_used=False,
            error="parse",
            detail=str(exc),
        )

    return _with_guides(
        top=top,
        scores=scores,
        guides=guides,
        groq_used=True,
        model=model,
    )

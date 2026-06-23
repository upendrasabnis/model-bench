#!/usr/bin/env python3
"""
OpenRouter free-model benchmark runner.

One run = for each enabled category (code / text / email / image), obtain a prompt
(generate it with an AI model, use a manual prompt, or use a fallback seed) and send
that SAME prompt to every configured model. For each model call we capture tokens in,
tokens out, latency, response length and the full response, then write everything to:

  - results.xlsx            full archive, one sheet per category (metrics + preview + file path)
  - results.json            last N runs, consumed by the dashboard
  - responses/<run-id>/...  full response text per model
  - images/<run-id>/...     generated images (only when the image category is enabled)

All behaviour is driven by config.json. See README.md.

Env:
  OPENROUTER_API_KEY   required, your OpenRouter API key
"""

import base64
import json
import os
import re
import sys
import time
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone

import requests
from openpyxl import Workbook, load_workbook

# --------------------------------------------------------------------------- #
# Paths
# --------------------------------------------------------------------------- #
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG_PATH = os.path.join(ROOT, "config.json")
STATE_PATH = os.path.join(ROOT, "state.json")
HISTORY_PATH = os.path.join(ROOT, "prompt_history.json")
RESULTS_XLSX = os.path.join(ROOT, "results.xlsx")
RESULTS_JSON = os.path.join(ROOT, "results.json")
RESPONSES_DIR = os.path.join(ROOT, "responses")
IMAGES_DIR = os.path.join(ROOT, "images")
PROMPTS_DIR = os.path.join(ROOT, "prompts")

API_URL = "https://openrouter.ai/api/v1/chat/completions"

CATEGORIES = ["code", "text", "email", "image"]
EXCEL_CELL_LIMIT = 32000  # below Excel's hard 32,767 limit, with headroom

# In-flight counter for the concurrency sanity check (verification step 2).
_inflight_lock = threading.Lock()
_inflight = 0
_inflight_max = 0


# --------------------------------------------------------------------------- #
# Small helpers
# --------------------------------------------------------------------------- #
def log(msg):
    print(f"[{datetime.now(timezone.utc).strftime('%H:%M:%S')}] {msg}", flush=True)


def load_json(path, default=None):
    if not os.path.exists(path):
        return default
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def save_json(path, data):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def slugify(value):
    return re.sub(r"[^a-zA-Z0-9._-]+", "_", value).strip("_") or "model"


def cat_setting(config, category, key, default=None):
    """Per-category override falling back to defaults block then a literal default."""
    cat = config.get("categories", {}).get(category, {})
    if key in cat:
        return cat[key]
    if key in config.get("defaults", {}):
        return config["defaults"][key]
    return default


# --------------------------------------------------------------------------- #
# OpenRouter call with retry / timeout
# --------------------------------------------------------------------------- #
def call_model(api_key, model, prompt, *, max_tokens, temperature, timeout,
               max_retries, backoff, want_image=False):
    """
    Returns a dict with metrics and either the text/image or an error.
    Retries on timeouts, 429 and 5xx with exponential backoff.
    """
    global _inflight, _inflight_max

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "HTTP-Referer": "https://github.com/model-bench",
        "X-Title": "model-bench",
    }
    body = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": max_tokens,
        "temperature": temperature,
    }
    if want_image:
        body["modalities"] = ["image", "text"]

    last_err = None
    for attempt in range(1, max_retries + 1):
        with _inflight_lock:
            _inflight += 1
            _inflight_max = max(_inflight_max, _inflight)
        start = time.monotonic()
        try:
            resp = requests.post(API_URL, headers=headers, json=body, timeout=timeout)
            latency_ms = int((time.monotonic() - start) * 1000)

            if resp.status_code == 200:
                data = resp.json()
                choice = (data.get("choices") or [{}])[0]
                message = choice.get("message", {}) or {}
                text = message.get("content") or ""
                usage = data.get("usage", {}) or {}

                images_b64 = []
                for img in message.get("images", []) or []:
                    url = (img.get("image_url") or {}).get("url", "")
                    if url.startswith("data:") and "," in url:
                        images_b64.append(url.split(",", 1)[1])

                return {
                    "status": "ok",
                    "http_status": 200,
                    "tokens_in": usage.get("prompt_tokens"),
                    "tokens_out": usage.get("completion_tokens"),
                    "total_tokens": usage.get("total_tokens"),
                    "latency_ms": latency_ms,
                    "text": text,
                    "images_b64": images_b64,
                    "error": "",
                    "attempts": attempt,
                }

            # Retryable HTTP statuses
            if resp.status_code == 429 or 500 <= resp.status_code < 600:
                last_err = f"HTTP {resp.status_code}: {resp.text[:200]}"
                if attempt < max_retries:
                    time.sleep(backoff * (2 ** (attempt - 1)))
                    continue
            # Non-retryable
            return {
                "status": "error",
                "http_status": resp.status_code,
                "tokens_in": None, "tokens_out": None, "total_tokens": None,
                "latency_ms": latency_ms,
                "text": "", "images_b64": [],
                "error": f"HTTP {resp.status_code}: {resp.text[:300]}",
                "attempts": attempt,
            }
        except (requests.Timeout, requests.ConnectionError) as e:
            last_err = f"{type(e).__name__}: {e}"
            if attempt < max_retries:
                time.sleep(backoff * (2 ** (attempt - 1)))
                continue
        except Exception as e:  # noqa: BLE001 - record anything unexpected, never crash the run
            last_err = f"{type(e).__name__}: {e}"
            break
        finally:
            with _inflight_lock:
                _inflight -= 1

    return {
        "status": "error",
        "http_status": None,
        "tokens_in": None, "tokens_out": None, "total_tokens": None,
        "latency_ms": None,
        "text": "", "images_b64": [],
        "error": last_err or "unknown error",
        "attempts": max_retries,
    }


# --------------------------------------------------------------------------- #
# Prompt sourcing
# --------------------------------------------------------------------------- #
def rotate(items, cycle):
    return items[cycle % len(items)] if items else None


def generate_prompt(api_key, config, category, meta_prompt, history, cycle):
    """Ask the generator model for a fresh prompt; raises on failure."""
    recent = history.get(category, [])[-config.get("prompt_history_keep", 20):]
    avoid = ""
    if recent:
        joined = "\n".join(f"- {p[:300]}" for p in recent)
        avoid = ("\n\nDo NOT repeat or closely resemble any of these recently used "
                 f"prompts:\n{joined}")
    instruction = (
        f"{meta_prompt}\n\n(Variation seed: cycle {cycle}.)"
        f"{avoid}\n\nRemember: output ONLY the prompt/text itself, with no preamble."
    )
    result = call_model(
        api_key,
        config["generator_model"],
        instruction,
        max_tokens=2000,
        temperature=config.get("generator_temperature", 0.9),
        timeout=config["request_timeout_seconds"],
        max_retries=config["max_retries"],
        backoff=config["retry_backoff_seconds"],
    )
    if result["status"] != "ok" or not result["text"].strip():
        raise RuntimeError(result.get("error") or "empty generation")
    return result["text"].strip()


def resolve_prompt(api_key, config, category, generators, manual, fallback, history, cycle):
    """
    Returns (raw_prompt, source_used). raw_prompt is the category content
    (for email/image this is the generated email/feature text, not yet wrapped).
    """
    source = cat_setting(config, category, "prompt_source", "generate")

    if source == "manual":
        entry = manual.get(category)
        if isinstance(entry, list):
            entry = rotate(entry, cycle)
        if not entry:
            raise RuntimeError(f"no manual prompt for '{category}' in manual.json")
        return entry, "manual"

    if source == "fallback":
        entry = rotate(fallback.get(category, []), cycle)
        if not entry:
            raise RuntimeError(f"no fallback prompt for '{category}' in fallback.json")
        return entry, "fallback"

    # generate
    try:
        return generate_prompt(api_key, config, category, generators[category], history, cycle), "generate"
    except Exception as e:  # noqa: BLE001
        if config.get("generator_fallback_on_error", True):
            log(f"  ! generation failed for {category} ({e}); using fallback")
            entry = rotate(fallback.get(category, []), cycle)
            if entry:
                return entry, "fallback"
        raise


def wrap_for_models(category, raw_prompt):
    """Turn the category content into the actual prompt sent to every model."""
    if category == "email":
        return (
            "You are a professional working in grants administration. Reply to the "
            "following email. Your reply must be professional, address every point "
            "raised, and be at least 200 words long.\n\n"
            f"--- EMAIL ---\n{raw_prompt}\n--- END EMAIL ---"
        )
    if category == "image":
        return f"Generate an image that visually explains this feature:\n\n{raw_prompt}"
    # code / text: the generated prompt is already the instruction
    return raw_prompt


# --------------------------------------------------------------------------- #
# Persistence
# --------------------------------------------------------------------------- #
EXCEL_HEADERS = [
    "run_id", "timestamp_utc", "cycle", "category", "prompt_source", "prompt_title",
    "model_id", "status", "http_status", "tokens_in", "tokens_out", "total_tokens",
    "latency_ms", "response_chars", "response_words", "attempts",
    "response_file", "image_file", "error",
]


def append_to_excel(rows):
    if os.path.exists(RESULTS_XLSX):
        wb = load_workbook(RESULTS_XLSX)
    else:
        wb = Workbook()
        wb.remove(wb.active)

    for category in CATEGORIES:
        cat_rows = [r for r in rows if r["category"] == category]
        if not cat_rows:
            continue
        if category in wb.sheetnames:
            ws = wb[category]
        else:
            ws = wb.create_sheet(title=category)
            ws.append(EXCEL_HEADERS)
        for r in cat_rows:
            ws.append([r.get(h, "") for h in EXCEL_HEADERS])

    if not wb.sheetnames:  # nothing ran; keep a valid workbook
        wb.create_sheet(title="empty")
    wb.save(RESULTS_XLSX)


def update_results_json(rows, run_meta, keep_runs):
    data = load_json(RESULTS_JSON, default={"runs": []})
    if not isinstance(data, dict):
        data = {"runs": []}
    compact = []
    for r in rows:
        compact.append({k: r.get(k) for k in (
            "category", "prompt_source", "prompt_title", "model_id", "status",
            "tokens_in", "tokens_out", "latency_ms", "response_chars",
            "response_words", "response_file", "image_file", "error",
        )})
    data["runs"].insert(0, {**run_meta, "results": compact})
    data["runs"] = data["runs"][:keep_runs]
    save_json(RESULTS_JSON, data)


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #
def main():
    api_key = os.environ.get("OPENROUTER_API_KEY")
    if not api_key:
        log("ERROR: OPENROUTER_API_KEY not set")
        sys.exit(1)

    config = load_json(CONFIG_PATH)
    state = load_json(STATE_PATH, default={"cycle": 0, "last_run_utc": None})
    history = load_json(HISTORY_PATH, default={c: [] for c in CATEGORIES})
    generators = load_json(os.path.join(PROMPTS_DIR, "generators.json"), default={})
    manual = load_json(os.path.join(PROMPTS_DIR, "manual.json"), default={})
    fallback = load_json(os.path.join(PROMPTS_DIR, "fallback.json"), default={})

    cycle = int(state.get("cycle", 0))
    now = datetime.now(timezone.utc)
    run_id = now.strftime("%Y%m%dT%H%M%SZ")
    log(f"=== Run {run_id} (cycle {cycle}) ===")

    enabled = [c for c in CATEGORIES if config["categories"].get(c, {}).get("enabled")]
    log(f"Enabled categories: {enabled}")

    # 1) Resolve one prompt per enabled category.
    prompts = {}  # category -> {raw, wrapped, source, title}
    for category in enabled:
        try:
            raw, source = resolve_prompt(
                api_key, config, category, generators, manual, fallback, history, cycle)
        except Exception as e:  # noqa: BLE001
            log(f"  ! skipping {category}: could not obtain prompt ({e})")
            continue
        max_in = cat_setting(config, category, "max_input_chars", 8000)
        raw = raw[:max_in]
        wrapped = wrap_for_models(category, raw)
        title = re.sub(r"\s+", " ", raw).strip()[:80]
        prompts[category] = {"raw": raw, "wrapped": wrapped, "source": source, "title": title}
        history.setdefault(category, []).append(raw)
        history[category] = history[category][-config.get("prompt_history_keep", 20):]
        log(f"  [{category}] prompt via {source}: {title}")

    # 2) Build the work list: (category, model) -> one request.
    tasks = []
    for category, p in prompts.items():
        models = config["categories"].get(category, {}).get("models_override") or config["models"]
        for model in models:
            tasks.append((category, model, p))
    log(f"Dispatching {len(tasks)} requests across max {config['max_in_flight']} in flight")

    # 3) Execute with bounded concurrency.
    rows = []

    def run_task(task):
        category, model, p = task
        want_image = category == "image"
        res = call_model(
            api_key, model, p["wrapped"],
            max_tokens=cat_setting(config, category, "max_output_tokens", 4096),
            temperature=cat_setting(config, category, "temperature", 0.7),
            timeout=config["request_timeout_seconds"],
            max_retries=config["max_retries"],
            backoff=config["retry_backoff_seconds"],
            want_image=want_image,
        )
        return category, model, p, res

    with ThreadPoolExecutor(max_workers=config["max_in_flight"]) as pool:
        futures = [pool.submit(run_task, t) for t in tasks]
        for fut in as_completed(futures):
            category, model, p, res = fut.result()
            rows.append(persist_one(run_id, cycle, now, category, model, p, res, config))
            log(f"  done {category}/{model}: {res['status']} "
                f"({res.get('latency_ms')}ms, out={res.get('tokens_out')})")

    # 4) Write Excel + JSON.
    if rows:
        append_to_excel(rows)
        update_results_json(
            rows,
            {"run_id": run_id, "timestamp_utc": now.isoformat(), "cycle": cycle,
             "prompts": {c: prompts[c]["title"] for c in prompts}},
            config.get("dashboard_history_runs", 500),
        )

    # 5) Advance state + history.
    state["cycle"] = cycle + 1
    state["last_run_utc"] = now.isoformat()
    save_json(STATE_PATH, state)
    save_json(HISTORY_PATH, history)

    log(f"Wrote {len(rows)} rows. Peak in-flight: {_inflight_max}. Done.")


def persist_one(run_id, cycle, now, category, model, p, res, config):
    """Write the full response to disk and return the metrics row."""
    slug = slugify(model)
    ext = "java" if category == "code" else "md"
    rel_dir = os.path.join("responses", run_id, category)
    abs_dir = os.path.join(ROOT, rel_dir)
    os.makedirs(abs_dir, exist_ok=True)

    response_file = ""
    text = res.get("text") or ""
    if text:
        store_limit = cat_setting(config, category, "max_response_store_chars", 200000)
        rel_path = os.path.join(rel_dir, f"{slug}.{ext}")
        with open(os.path.join(ROOT, rel_path), "w", encoding="utf-8") as f:
            header = (f"<!-- model: {model} | category: {category} | run: {run_id} -->\n"
                      f"<!-- PROMPT -->\n{p['wrapped']}\n<!-- RESPONSE -->\n")
            f.write(header + text[:store_limit])
        response_file = rel_path

    image_file = ""
    for i, b64 in enumerate(res.get("images_b64", [])):
        img_rel_dir = os.path.join("images", run_id, category)
        os.makedirs(os.path.join(ROOT, img_rel_dir), exist_ok=True)
        img_rel = os.path.join(img_rel_dir, f"{slug}_{i}.png")
        try:
            with open(os.path.join(ROOT, img_rel), "wb") as f:
                f.write(base64.b64decode(b64))
            image_file = img_rel
        except Exception as e:  # noqa: BLE001
            res["error"] = (res.get("error") or "") + f" [image decode: {e}]"

    return {
        "run_id": run_id,
        "timestamp_utc": now.isoformat(),
        "cycle": cycle,
        "category": category,
        "prompt_source": p["source"],
        "prompt_title": p["title"],
        "model_id": model,
        "status": res["status"],
        "http_status": res.get("http_status") or "",
        "tokens_in": res.get("tokens_in") if res.get("tokens_in") is not None else "",
        "tokens_out": res.get("tokens_out") if res.get("tokens_out") is not None else "",
        "total_tokens": res.get("total_tokens") if res.get("total_tokens") is not None else "",
        "latency_ms": res.get("latency_ms") if res.get("latency_ms") is not None else "",
        "response_chars": len(text),
        "response_words": len(text.split()),
        "attempts": res.get("attempts", 1),
        "response_file": response_file,
        "image_file": image_file,
        "error": (res.get("error") or "")[:EXCEL_CELL_LIMIT],
    }


if __name__ == "__main__":
    main()

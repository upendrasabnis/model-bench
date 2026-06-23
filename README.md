# OpenRouter Free-Model Benchmark

Sends the **same prompt to many OpenRouter models** every hour and records, per model:
tokens in, tokens out, response time, response length, and the full response — into an
**Excel file** (`results.xlsx`), a JSON feed (`results.json`) for the dashboard, and
full-text files under `responses/`.

It rotates through four task categories every run:

| Category | What it tests |
|----------|---------------|
| `code`   | Write a complex multi-threaded Java class + JUnit tests |
| `text`   | Write a ~1000-line article on a Grants topic |
| `email`  | Generate a realistic inbound email, then each model replies (≥200 words) |
| `image`  | Generate an image explaining a web-app feature (**off by default** — no free image models on OpenRouter) |

It runs entirely on **GitHub** — no server. A scheduled GitHub Action runs the job hourly
and commits the results back to the repo (same pattern as the badminton tracker); GitHub
Pages serves a read-only dashboard.

---

## How it works

```
GitHub Actions (hourly cron)  →  scripts/run_benchmark.py  →  commit results.* + responses/
                                                                      │
                                          push to main  →  Deploy workflow  →  GitHub Pages (index.html)
```

Each run:
1. For each enabled category, obtain a prompt (AI-generated, manual, or fallback — see below).
2. Send that prompt to every configured model, **max 10 requests in flight**, with retry + timeout.
3. Capture metrics + full response, append to `results.xlsx` (one sheet per category) and `results.json`.
4. Save full responses to `responses/<run-id>/<category>/<model>.(md|java)`.
5. Advance the cycle counter and prompt history, then commit & push everything.

---

## Configuration — everything lives in `config.json`

| Key | Meaning |
|-----|---------|
| `max_in_flight` | Max concurrent requests (default 10) |
| `request_timeout_seconds` | Per-request timeout |
| `max_retries` / `retry_backoff_seconds` | Retry attempts + exponential backoff base (on timeout, 429, 5xx) |
| `models` | The curated list of free models to benchmark |
| `generator_model` | Which model writes the prompts in `"generate"` mode |
| `generator_temperature`, `prompt_history_keep`, `generator_fallback_on_error` | Prompt-generation behaviour |
| `defaults.max_input_chars` | Truncate the prompt sent to models |
| `defaults.max_output_tokens` | `max_tokens` sent on each request |
| `defaults.max_response_store_chars` | How much full text to persist |
| `dashboard_history_runs` | How many runs to keep in `results.json` |
| `categories.<name>.enabled` | Turn a category on/off |
| `categories.<name>.prompt_source` | `"generate"` \| `"manual"` \| `"fallback"` |
| `categories.<name>.models_override` | Use a different model subset for this category |
| `categories.<name>.max_output_tokens` / `max_input_chars` / `temperature` | Per-category overrides |

### Choosing where prompts come from
- **`generate`** (default): `generator_model` writes a fresh prompt each run, told to avoid the
  last `prompt_history_keep` prompts (stored in `prompt_history.json`).
- **`manual`**: uses your text in `prompts/manual.json` (string, or a list rotated by cycle).
- **`fallback`**: rotates the static seeds in `prompts/fallback.json`.

The generator meta-prompts live in `prompts/generators.json`.

### Enabling image generation (costs money)
OpenRouter has **no free image models**. To enable: set `categories.image.enabled` to `true`,
put a paid image model in `categories.image.models_override` (e.g. `google/gemini-3.1-flash-image`),
and make sure your OpenRouter account has credits.

---

## One-time setup (mirrors the tracker)

1. Create a GitHub repo and push this folder.
2. **Settings → Secrets and variables → Actions → New secret**: `OPENROUTER_API_KEY` = your key.
3. **Settings → Pages**: source = "GitHub Actions" (the deploy workflow auto-enables it on first push).
4. **Actions → Benchmark Run → Run workflow** to trigger the first run manually, then the hourly
   cron takes over.

---

## Run locally

```bash
pip install -r requirements.txt
export OPENROUTER_API_KEY=sk-or-...
python scripts/run_benchmark.py
python3 -m http.server 8000   # then open http://localhost:8000 for the dashboard
```

---

## Caveats

- GitHub scheduled crons are **best-effort** and can be delayed under load.
- OpenRouter free models have **per-minute and daily rate limits** — some runs may show `429`s
  (handled by the concurrency cap + backoff, and recorded as errors rather than crashing).
- Free models **cap output length**, so a "1000-line article" request will usually return less —
  that's itself a useful benchmark signal.
- If the repo is **public**, all results and responses are public.

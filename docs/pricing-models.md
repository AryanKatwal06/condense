# LLM Pricing Catalog and Cost Estimation

Condense provides auditable, model-aware dollar savings estimates in `condense gain`. All cost calculations are computed locally against a versioned, static pricing catalog. Condense makes **zero runtime network calls** and sends no telemetry.

---

## 1. Architecture and Design Principles

- **Offline and Deterministic**: Rates are packaged directly into the binary from `pricing/models.json`. Cost estimates never depend on external network availability or third-party uptime.
- **Fail-Open**: If the pricing catalog resource cannot be parsed or an unknown model is requested, Condense prints a descriptive warning to `stderr`, suppresses dollar figures, and continues outputting token metrics without crashing.
- **Auditable Provenance**: Every output explicitly states the model ID, input/output rates per million tokens, effective date, vendor pricing URL, and estimation uncertainty.
- **Explicit Overrides**: No hidden auto-detection of models or environment variables. Models are specified via `--model <name>`, configured in `config.toml` under `[analytics] model`, or defaulted to `claude-3-5-sonnet-20241022`.

---

## 2. CLI Flags and Configuration

### Command-Line Flags

```bash
# Display token savings and dollar savings with default model (Claude 3.5 Sonnet)
condense gain

# Target a specific model (supports exact ID or friendly alias)
condense gain --model gpt-4o
condense gain --model sonnet
condense gain --model gemini-1.5-flash

# List all available models, rates, effective dates, and vendor sources
condense gain --list-models

# Machine-readable formats with cost metrics
condense gain --format json
condense gain --format csv
condense gain --daily --format csv
```

### Configuration (`config.toml`)

Set a persistent default model in `~/.config/condense/config.toml` (Linux/macOS) or `%APPDATA%\condense\config.toml` (Windows):

```toml
[analytics]
model = "gpt-4o"
```

Resolution precedence:
1. CLI flag `--model <name>`
2. Config file `[analytics] model`
3. Built-in default (`claude-3-5-sonnet-20241022`)

---

## 3. Supported Models Catalog

Below are the default models bundled in `pricing/models.json` (effective catalog date: 2026-09-01):

| Model ID | Provider | Aliases | Input / 1M | Output / 1M | Effective Date | Source URL |
|---|---|---|---|---|---|---|
| `claude-3-5-sonnet-20241022` *(default)* | Anthropic | `sonnet`, `claude-3-5-sonnet` | $3.00 | $15.00 | 2024-10-22 | [Anthropic Pricing](https://www.anthropic.com/pricing) |
| `claude-3-5-haiku-20241022` | Anthropic | `haiku`, `claude-3-5-haiku` | $0.80 | $4.00 | 2024-11-04 | [Anthropic Pricing](https://www.anthropic.com/pricing) |
| `claude-3-opus-20240229` | Anthropic | `opus`, `claude-3-opus` | $15.00 | $75.00 | 2024-03-01 | [Anthropic Pricing](https://www.anthropic.com/pricing) |
| `gpt-4o-2024-11-20` | OpenAI | `gpt-4o` | $2.50 | $10.00 | 2024-11-20 | [OpenAI Pricing](https://openai.com/api/pricing) |
| `gpt-4o-mini-2024-07-18` | OpenAI | `gpt-4o-mini`, `4o-mini` | $0.15 | $0.60 | 2024-07-18 | [OpenAI Pricing](https://openai.com/api/pricing) |
| `o1-2024-12-17` | OpenAI | `o1` | $15.00 | $60.00 | 2024-12-17 | [OpenAI Pricing](https://openai.com/api/pricing) |
| `o1-mini-2024-09-12` | OpenAI | `o1-mini` | $1.10 | $4.40 | 2024-09-12 | [OpenAI Pricing](https://openai.com/api/pricing) |
| `gemini-1.5-pro-002` | Google | `gemini-1.5-pro`, `gemini-pro` | $1.25 | $5.00 | 2024-09-24 | [Google AI Pricing](https://ai.google.dev/pricing) |
| `gemini-1.5-flash-002` | Google | `gemini-1.5-flash`, `gemini-flash` | $0.075 | $0.30 | 2024-09-24 | [Google AI Pricing](https://ai.google.dev/pricing) |
| `deepseek-chat` | DeepSeek | `deepseek-v3` | $0.14 | $0.28 | 2024-12-26 | [DeepSeek Pricing](https://api-docs.deepseek.com/quick_start/pricing) |
| `deepseek-reasoner` | DeepSeek | `deepseek-r1` | $0.55 | $2.19 | 2025-01-20 | [DeepSeek Pricing](https://api-docs.deepseek.com/quick_start/pricing) |

---

## 4. Token Estimator Calibration and Uncertainty Disclosure

Condense uses the embedded `utf8_weighted_v1` estimator to calculate token savings without incurring runtime BPE overhead or embedding heavy vocabulary tables in the native binary.

- **Benchmark Reference**: `cl100k_base` (OpenAI Tiktoken vocabulary, matching GPT-4 and Claude token density characteristics).
- **Published Precision**: p95 relative error is bounded within **±37%** on representative developer workloads.
- **Uncertainty Disclosure**: All dollar savings figures inherit this ±37% uncertainty:
  $$\text{USD Savings} = \frac{\text{Tokens Saved} \times \text{Input Rate per Million}}{1,000,000} \pm 37\%$$
- **Mixed History Handling**: The SQLite analytics schema version 3 tags each recorded command with its estimator version (`utf8_weighted_v1`) and schema version (`3`). When queries span across database migrations or differing estimators, Condense flags `history_status = "mixed_estimators"` and adds a disclosure note in summary panels.

---

## 5. Automated Calibration and Freshness Reviews

A scheduled GitHub Actions workflow runs quarterly (`.github/workflows/calibration-and-pricing-review.yml`) to:
1. Execute `TokenCalibrationTest` across Python, Java, TypeScript, Rust, NDJSON logs, syslog, git diffs, and multilingual samples to ensure error bounds remain stable.
2. Execute `PricingCatalogFreshnessTest` to validate pricing schema integrity, valid HTTPS vendor sources, and unique alias maps.
3. Audit catalog model effective dates against a 180-day threshold and automatically open GitHub tracking issues if vendor pricing pages require quarterly review.

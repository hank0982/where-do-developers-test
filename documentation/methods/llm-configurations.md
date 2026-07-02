# LLM Configuration for Replication

This document records the LLM configurations that are observable from the request builders and the emitted request JSONL files in this workspace.

## Scope and Interpretation

- `version` is reported as the exact model identifier stored in the request payloads.
- The repository does not record provider-side internal build hashes, so those are not recoverable here.
- The strongest evidence is the emitted request JSONL under `dbs/`; builder defaults are listed separately when useful.

## Main Model Inventory

| Family | Recorded model id / version | Main role in pipeline | Temperature | Top-p | Top-k | Seed | Thinking | Evidence |
| --- | --- | --- | ---: | ---: | ---: | ---: | --- | --- |
| Qwen | `qwen3.5:cloud` | Ollama-backed v4 chain and final reconciliation | `0.1` | `0.8` | `40` | `42` | omitted in observed main runs | `scripts/LLM_batch/run_v4_ollama.py`, `scripts/LLM_batch/request_provider_ollama.py`, `dbs/batch_input_commons-validator/attempt_v4_ollama_qwen_complete/ollama-requests.qwen3.5-cloud.final-compress.jsonl` |
| DeepSeek | `deepseek-v3.2:cloud` | Ollama-backed v4 chain and final reconciliation | usually `0.1`; some earlier request sets use `0.3` | `0.8` | `40` | `42` | usually omitted; one observed variant uses `medium` | `scripts/LLM_batch/run_v4_ollama.py`, `scripts/LLM_batch/request_provider_ollama.py`, `dbs/batch_input_commons-validator/attempt_v4_ollama_deepseek/ollama-requests.deepseek-v3.2-cloud.final-compress.jsonl` |
| Gemini Pro | alias `gemini-3` resolves to `models/gemini-3-pro-preview` | Gemini v4 chain | `0.1` | `0.9` | not used | `42` | usually omitted; rare observed variants use `minimal` or `low` | `scripts/LLM_batch/request_provider_gemini.py`, `scripts/LLM_batch/run_v4_gemini_chain.py`, `dbs/batch_input_commons-validator/gemini-batch-requests.gemini-3.jsonl` |
| Gemini Flash | `models/gemini-3-flash-preview` | Gemini v4 chain | `0.1` | `0.9` | not used | `42` | usually omitted; rare observed variants use `minimal` or `low` | `scripts/LLM_batch/request_provider_gemini.py`, `scripts/LLM_batch/run_v4_gemini_chain.py`, `dbs/batch_input_commons-validator/gemini-batch-requests.gemini-3-flash-preview.jsonl` |

## Builder Defaults

### Ollama-backed requests

The common Ollama request builder is `scripts/LLM_batch/request_provider_ollama.py`. It emits:

- `model`
- `messages` with separate system and user turns
- `options.temperature`
- `options.top_p`
- `options.top_k`
- `options.seed`
- optional `think`
- JSON schema under `format`

The v4 Ollama chain defaults to:

- `--ollama-model qwen3.5:cloud`
- temperature passed in as `0.1` in the v4 chain
- `top_p=0.8`
- `top_k=40`
- `seed=42`

Relevant source paths:

- `scripts/LLM_batch/request_provider_ollama.py`
- `scripts/LLM_batch/run_v4_ollama.py`

## Gemini request construction

The Gemini request builder is `scripts/LLM_batch/request_provider_gemini.py`.

Observed and coded behavior:

- alias `gemini-3` maps to `models/gemini-3-pro-preview`
- alias `models/gemini-3` also maps to `models/gemini-3-pro-preview`
- alias `gemini-3-flash-preview` maps to `models/gemini-3-flash-preview`
- `generation_config.temperature = 0.1`
- `generation_config.top_p = 0.9`
- `generation_config.seed = 42`
- `response_mime_type = application/json`
- schema is embedded as `response_json_schema`
- optional `thinking_config` may include `thinking_level` and `thinking_budget`

Relevant source paths:

- `scripts/LLM_batch/request_provider_gemini.py`
- `scripts/LLM_batch/run_v4_gemini_chain.py`

## Observed Variants

These variants appear in archived request sets and should be documented for completeness:

- DeepSeek at `temperature=0.3` appears in older request files such as `dbs/ollama-requests.deepseek-v3.2-cloud.jsonl`.
- Gemini `thinking_level=minimal` appears in `dbs/batch_input_jfreechart/attempt_v8/gemini-batch-requests.gemini-3-flash-preview.jsonl` and `.../gemini-3.jsonl`.
- Gemini `thinking_level=low` appears in `dbs/batch_input_jfreechart/attempt_v9_complete/gemini-batch-requests.gemini-3-flash-preview.jsonl` and `.../gemini-3.jsonl`.

These are exceptions, not the dominant configuration.

## Archived Earlier Experiments

Older request files also contain earlier Gemini identifiers:

- `gemini-1.5-pro`
- `gemini-3`
- `gemini-3-flash`

These appear in archived files such as `dbs/annotation-attempt-1/gemini-batch-requests*.jsonl`. They should be treated as historical experiments unless a specific replication target requires them.

## Suggested Citation Text

If you need a short methods sentence later, this is faithful to the repository:

> We issued JSON-constrained focal-method annotation requests to `qwen3.5:cloud`, `deepseek-v3.2:cloud`, `models/gemini-3-pro-preview`, and `models/gemini-3-flash-preview`, using low-temperature settings (`0.1` for the main chain), fixed seeds where supported, and provider-specific top-p/top-k defaults recorded in the emitted request payloads.

## Request JSON Formatting by Provider

This section records how the repository formats requests for each provider API or client library.

### Ollama request format

The Ollama-backed requests for Qwen and DeepSeek are built by `scripts/LLM_batch/request_provider_ollama.py`.

The payload shape is:

```json
{
  "model": "qwen3.5:cloud",
  "messages": [
    {"role": "system", "content": "<system prompt>"},
    {"role": "user", "content": "<user prompt>"}
  ],
  "options": {
    "temperature": 0.1,
    "top_p": 0.8,
    "top_k": 40,
    "seed": 42
  },
  "format": {
    "type": "object",
    "properties": {
      "focal_methods": {
        "type": "array"
      }
    }
  },
  "stream": false,
  "custom_id": "repo::test_id::stage"
}
```

Important details:

- The repository uses the Ollama chat-style `messages` format.
- JSON output is constrained through the `format` field.
- Provider sampling parameters live under `options`.
- Optional reasoning mode, when used, is attached as top-level `think`.

Relevant source path:

- `scripts/LLM_batch/request_provider_ollama.py`

### Gemini request format

The Gemini-backed requests are built by `scripts/LLM_batch/request_provider_gemini.py`.

The payload shape is:

```json
{
  "key": "repo::test_id::stage",
  "request": {
    "model": "models/gemini-3-pro-preview",
    "system_instruction": {
      "parts": [
        {"text": "<system prompt>"}
      ]
    },
    "contents": [
      {
        "role": "user",
        "parts": [
          {"text": "<user prompt>"}
        ]
      }
    ],
    "generation_config": {
      "temperature": 0.1,
      "top_p": 0.9,
      "seed": 42,
      "response_mime_type": "application/json",
      "response_json_schema": {
        "type": "object",
        "properties": {
          "focal_methods": {
            "type": "array"
          },
          "requested_methods": {
            "type": "array"
          }
        }
      }
    }
  }
}
```

When thinking is enabled, it is encoded inside `generation_config.thinking_config`, for example:

```json
{
  "thinking_config": {
    "thinking_level": "low"
  }
}
```

Important details:

- The repository uses the Gemini batch-request JSONL format, with one top-level object per line.
- The actual request body is nested under `request`.
- The system prompt goes in `request.system_instruction.parts[0].text`.
- The user prompt goes in `request.contents[0].parts[0].text`.
- JSON output is constrained through `generation_config.response_json_schema`.
- The output MIME type is explicitly set to `application/json`.

Relevant source paths:

- `scripts/LLM_batch/request_provider_gemini.py`
- `scripts/submit_gemini_batch.py`

### Output schema used in this project

The repository uses two closely related JSON schemas, depending on stage.

Iterative stage:

```json
{
  "type": "object",
  "properties": {
    "focal_methods": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["test_id", "method_id", "class_name", "method_name"]
      }
    },
    "requested_methods": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["test_id", "method_id", "class_name", "method_name"]
      }
    }
  },
  "required": ["focal_methods", "requested_methods"],
  "additionalProperties": false
}
```

Final-compress stage:

```json
{
  "type": "object",
  "properties": {
    "focal_methods": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["test_id", "method_id", "class_name", "method_name"]
      }
    }
  },
  "required": ["focal_methods"],
  "additionalProperties": false
}
```

Relevant source paths:

- `scripts/LLM_batch/prompt.py`
- `scripts/LLM_batch/run_v4_ollama.py`
- `scripts/LLM_batch/run_v4_gemini_chain.py`

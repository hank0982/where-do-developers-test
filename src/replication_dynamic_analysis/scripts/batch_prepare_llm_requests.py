#!/usr/bin/env python3
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Optional


def model_tag(model: str) -> str:
    normalized = re.sub(r"[^A-Za-z0-9._-]+", "-", model.strip())
    normalized = normalized.strip("-")
    return normalized or "model"


def build_few_shot_examples() -> str:
    answer1 = (
        "{\"focal_methods\":[{\"test_id\":1620,\"method_id\":734,"
        "\"class_name\":\"org.jsoup.nodes.Element\",\"method_name\":\"select\"}]}"
    )
    answer2 = (
        "{\"focal_methods\":[{\"test_id\":1808,\"method_id\":999,"
        "\"class_name\":\"org.dyn4j.collision.narrowphase.Sat\",\"method_name\":\"detect\"},"
        "{\"test_id\":1808,\"method_id\":1000,"
        "\"class_name\":\"org.dyn4j.collision.narrowphase.Sat\",\"method_name\":\"detect\"}]}"
    )
    answer3 = (
        "{\"focal_methods\":[{\"test_id\":1252,\"method_id\":2644,"
        "\"class_name\":\"io.dropwizard.util.Duration\",\"method_name\":\"parse\"}]}"
    )
    return (
        "Examples:\n"
        "Test ID: 1620\n"
        "Test name: org.jsoup.select.SelectorTest_descendant_3a0746be\n"
        "Test method: descendant\n"
        "Invoked methods:\n"
        "- method_id=135 class_name=org.jsoup.Jsoup method_name=parse\n"
        "- method_id=734 class_name=org.jsoup.nodes.Element method_name=select\n"
        "- method_id=803 class_name=org.jsoup.nodes.Element method_name=getElementsByClass\n"
        "- method_id=822 class_name=org.jsoup.nodes.Element method_name=text\n"
        "- method_id=1666 class_name=org.jsoup.select.Elements method_name=first\n"
        "Test method body:\n"
        "@Test public void descendant() {\n"
        "  String h = \"<div class=head><p class=first>Hello</p><p>There</p></div><p>None</p>\";\n"
        "  Document doc = Jsoup.parse(h);\n"
        "  Element root = doc.getElementsByClass(\"HEAD\").first();\n"
        "  Elements els = root.select(\".head p\");\n"
        "  assertEquals(2, els.size());\n"
        "  Elements p = root.select(\"p.first\");\n"
        "  assertEquals(1, p.size());\n"
        "  Elements empty = root.select(\"p .first\");\n"
        "  assertEquals(0, empty.size());\n"
        "  Elements aboveRoot = root.select(\"body div.head\");\n"
        "  assertEquals(0, aboveRoot.size());\n"
        "}\n"
        f"Answer: {answer1}\n"
        "\n"
        "Test ID: 1808\n"
        "Test name: org.dyn4j.collision.shapes.SegmentCapsuleTest_detectSat_90e77643\n"
        "Test method: detectSat\n"
        "Invoked methods:\n"
        "- method_id=999 class_name=org.dyn4j.collision.narrowphase.Sat method_name=detect\n"
        "- method_id=1000 class_name=org.dyn4j.collision.narrowphase.Sat method_name=detect\n"
        "- method_id=1016 class_name=org.dyn4j.collision.narrowphase.Penetration method_name=getNormal\n"
        "- method_id=1017 class_name=org.dyn4j.collision.narrowphase.Penetration method_name=clear\n"
        "- method_id=1019 class_name=org.dyn4j.collision.narrowphase.Penetration method_name=getDepth\n"
        "- method_id=2455 class_name=org.dyn4j.geometry.Transform method_name=translate\n"
        "Test method body:\n"
        "@Test public void detectSat() {\n"
        "  Penetration p = new Penetration();\n"
        "  Transform t1 = new Transform();\n"
        "  Transform t2 = new Transform();\n"
        "  Vector2 n = null;\n"
        "  TestCase.assertTrue(this.sat.detect(segment, t1, capsule, t2, p));\n"
        "  TestCase.assertTrue(this.sat.detect(segment, t1, capsule, t2));\n"
        "  n = p.getNormal();\n"
        "  TestCase.assertEquals(0.388, p.getDepth(), 1.0e-3);\n"
        "  p.clear();\n"
        "  TestCase.assertTrue(this.sat.detect(capsule, t2, segment, t1, p));\n"
        "  TestCase.assertTrue(this.sat.detect(capsule, t2, segment, t1));\n"
        "  n = p.getNormal();\n"
        "  TestCase.assertEquals(0.388, p.getDepth(), 1.0e-3);\n"
        "  p.clear();\n"
        "  t1.translate(-0.5, 0.0);\n"
        "  TestCase.assertTrue(this.sat.detect(segment, t1, capsule, t2, p));\n"
        "  TestCase.assertTrue(this.sat.detect(segment, t1, capsule, t2));\n"
        "  n = p.getNormal();\n"
        "  TestCase.assertEquals(0.111, p.getDepth(), 1.0e-3);\n"
        "  p.clear();\n"
        "  TestCase.assertTrue(this.sat.detect(capsule, t2, segment, t1, p));\n"
        "  TestCase.assertTrue(this.sat.detect(capsule, t2, segment, t1));\n"
        "  n = p.getNormal();\n"
        "  TestCase.assertEquals(0.111, p.getDepth(), 1.0e-3);\n"
        "  p.clear();\n"
        "  t2.translate(0.1, 0.1);\n"
        "  TestCase.assertFalse(this.sat.detect(segment, t1, capsule, t2, p));\n"
        "  TestCase.assertFalse(this.sat.detect(segment, t1, capsule, t2));\n"
        "}\n"
        f"Answer: {answer2}\n"
        "\n"
        "Test ID: 1252\n"
        "Test name: io.dropwizard.util.DurationTest_parsesMinutes_c87dede0\n"
        "Test method: parsesMinutes\n"
        "Invoked methods:\n"
        "- method_id=2638 class_name=io.dropwizard.util.Duration method_name=minutes raw_method=minutes/1[long]\n"
        "- method_id=2644 class_name=io.dropwizard.util.Duration method_name=parse raw_method=parse/1[java.lang.String]\n"
        "- method_id=2647 class_name=io.dropwizard.util.Duration method_name=equals raw_method=equals/1[java.lang.Object]\n"
        "Test method body:\n"
        "package io.dropwizard.util;\n"
        "class DurationTest {\n"
        "    \n"
        "    @Test\n"
        "    void parsesMinutes() throws Exception {\n"
        "        assertThat(Duration.parse(\"1m\"))\n"
        "            .isEqualTo(Duration.minutes(1));\n"
        "\n"
        "        assertThat(Duration.parse(\"1min\"))\n"
        "            .isEqualTo(Duration.minutes(1));\n"
        "\n"
        "        assertThat(Duration.parse(\"2mins\"))\n"
        "            .isEqualTo(Duration.minutes(2));\n"
        "\n"
        "        assertThat(Duration.parse(\"1 minute\"))\n"
        "            .isEqualTo(Duration.minutes(1));\n"
        "\n"
        "        assertThat(Duration.parse(\"2 minutes\"))\n"
        "            .isEqualTo(Duration.minutes(2));\n"
        "    }\n"
        "\n"
        "}\n"
        f"Answer: {answer3}\n"
    )


def build_system_text() -> str:
    few_shot = build_few_shot_examples()
    return (
        "You are analyzing a JUnit test. "
        "From the invoked methods, identify which ones are the focal methods "
        "for this test method. If none qualify, return an empty array.\n"
        "\n"
        "Definition - Focal Method(s):\n"
        "A minimal (or near-minimal) set of SUT methods (including constructors) "
        "directly invoked by the test case whose behavior or interaction semantics "
        "capture the test's intent.\n"
        "\n"
        "Inclusion Criteria:\n"
        "1. (Already pre-filtered) Method/constructor is declared in application source code.\n"
        "2. (Already pre-filtered) Method is directly invoked by the test case (level-0 call).\n"
        "3. Method contributes to the core behavioral property being verified, either "
        "individually or through interaction with other focal methods.\n"
        "4. Prefer intent-specific methods (for example, methods whose configuration or "
        "arguments determine the tested scenario), without requiring comparison against other tests.\n"
        "\n"
        "Exclusion Criteria:\n"
        "1. Exclude methods/constructors defined outside application source code "
        "(for example JDK, third-party libraries, testing frameworks).\n"
        "2. Exclude methods not directly invoked by the test case (only deeper in call chain).\n"
        "3. Exclude methods used only for observation, scaffolding, setup, or state exposure "
        "(for example simple getters), unless that behavior itself is being validated.\n"
        "4. Exclude methods whose only role is wrapping/instantiating fixture setup used to "
        "reach behavior under test, unless the test asserts construction/initialization "
        "properties (for example default values, null handling, parameter validation, or initial configuration effects).\n"
        "\n"
        "with keys test_id, method_id, class_name, and method_name. "
        "No prose, no markdown, no code fences. Do not invent; must match invoked list exactly\n\n"
        f"{few_shot}"
    )


def build_prompt(record: dict[str, object]) -> str:
    invoked_methods = record.get("invoked_methods", [])
    methods_block = "\n".join(
        f"- method_id={m['method_id']} "
        f"class_name={m['class_name']} method_name={m['method_name']} "
        f"raw_method={m['raw_method']}"
        for m in invoked_methods
    )
    return (
        f"Test ID: {record['test_id']}\n"
        f"Test name: {record['test_name']}\n"
        f"Test method: {record['test_method']}\n\n"
        "Level-0 methods are directly invoked by the test method obtained through instrumentation. "
        "Only select methods from the invoked methods listed below. "
        "If none are focal methods, return an empty array.\n"
        
        "Invoked methods (from level_zero):\n"
        f"{methods_block}\n\n"
        "Test file content:\n"
        f"{record['file_content']}\n\n"
        "Question: Which methods are the focal methods?\n"
    )


def extract_method_block(file_content: str, line_number: object) -> str:
    if not isinstance(file_content, str) or not file_content:
        return ""
    try:
        line_num = int(line_number) if line_number is not None else None
    except (TypeError, ValueError):
        return ""
    if line_num is None:
        return ""
    lines = file_content.splitlines()
    if not lines:
        return ""
    start_line = max(0, min(line_num - 1, len(lines) - 1))
    brace_depth = 0
    started = False
    end_line = None
    for i in range(start_line, len(lines)):
        line = lines[i]
        for ch in line:
            if ch == "{":
                brace_depth += 1
                started = True
            elif ch == "}":
                if started:
                    brace_depth -= 1
                    if brace_depth == 0:
                        end_line = i
                        break
        if end_line is not None:
            break
    if not started or end_line is None:
        return ""
    while start_line > 0:
        prev = lines[start_line - 1].strip()
        if prev.startswith("@") or prev == "":
            start_line -= 1
            continue
        break
    return "\n".join(lines[start_line : end_line + 1])


def normalize_method_block(block: str) -> str:
    if not block:
        return ""
    normalized_lines = []
    for line in block.splitlines():
        stripped = " ".join(line.strip().split())
        if stripped:
            normalized_lines.append(stripped)
    return "\n".join(normalized_lines)


def normalize_invoked_methods(invoked_methods: object) -> str:
    if not isinstance(invoked_methods, list):
        return "[]"
    items = []
    for method in invoked_methods:
        if not isinstance(method, dict):
            continue
        items.append({
            "method_id": method.get("method_id"),
            "num_of_invoked": method.get("num_of_invoked"),
            "class_name": method.get("class_name"),
            "method_name": method.get("method_name"),
            "raw_method": method.get("raw_method"),
        })
    items.sort(key=lambda m: (
        str(m.get("method_id")),
        str(m.get("num_of_invoked")),
        str(m.get("class_name")),
        str(m.get("method_name")),
        str(m.get("raw_method")),
    ))
    return json.dumps(items, sort_keys=True)


def build_duplicate_signature(record: dict[str, object]) -> tuple[str, str]:
    invoked_key = normalize_invoked_methods(record.get("invoked_methods"))
    method_block = normalize_method_block(
        extract_method_block(
            record.get("file_content", ""),
            record.get("test_line"),
        )
    )
    signature = f"{invoked_key}\n---\n{method_block}"
    signature_id = hashlib.sha1(signature.encode("utf-8")).hexdigest()
    return signature_id, method_block


def build_exact_dedupe_key(record: dict[str, object]) -> str:
    payload = {
        "test_class": record.get("test_class"),
        "test_method": record.get("test_method"),
        "test_file": record.get("test_file"),
        "test_line": record.get("test_line"),
        "invoked_methods": normalize_invoked_methods(record.get("invoked_methods")),
    }
    return hashlib.sha1(json.dumps(payload, sort_keys=True).encode("utf-8")).hexdigest()


def build_openai_request(prompt: str,
                         custom_id: str,
                         model: str,
                         temperature: float,
                         top_p: float) -> dict[str, object]:
    # JSON Schema describing the entire response.
    schema = {
        "type": "object",
        "properties": {
            "focal_methods": {
                "type": "array",
                "items": {
                    "type": "object",
                    "required": ["test_id", "method_id", "class_name", "method_name"],
                    "properties": {
                        "test_id": {"type": "integer"},
                        "method_id": {"type": "integer"},
                        "class_name": {"type": "string"},
                        "method_name": {"type": "string"},
                    },
                    "additionalProperties": False,
                },
            }
        },
        "required": ["focal_methods"],
        "additionalProperties": False,
    }

    system_text = build_system_text()

    return {
        "custom_id": custom_id,
        "method": "POST",
        "url": "/v1/responses",
        "body": {
            "model": model,
            "input": [
                {
                    "role": "system",
                    "content": system_text,
                },
                {
                    "role": "user",
                    "content": prompt,
                },
            ],
            "text": {
                "format": {
                    "type": "json_schema",
                    "name": "focal_methods",
                    "schema": schema,
                    "strict": True,
                }
            },
        },
    }

def build_gemini_request(
    prompt: str,
    custom_id: str,
    model: str,
    temperature: float,
    top_p: float,
) -> dict[str, object]:
    # JSON Schema for the output
    gemini_json_schema = {
        "type": "object",
        "properties": {
            "focal_methods": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "test_id": {"type": "integer"},
                        "method_id": {"type": "integer"},
                        "class_name": {"type": "string"},
                        "method_name": {"type": "string"},
                    },
                    "required": [
                        "test_id",
                        "method_id",
                        "class_name",
                        "method_name",
                    ],
                },
                "description": (
                    "Array of focal methods for this JUnit test. "
                    "Empty if no focal methods are identified."
                ),
            }
        },
        "required": ["focal_methods"],
    }

    system_text = build_system_text()

    # IMPORTANT: this shape matches the working JSONL example you found:
    # - snake_case field names
    # - response_json_schema under generation_config
    return {
        "key": custom_id,
        "request": {
            "model": f"models/{model}" if not model.startswith("models/") else model,
            "system_instruction": {
                "parts": [{"text": system_text}],
            },
            "contents": [
                {
                    "role": "user",
                    "parts": [{"text": prompt}],
                }
            ],
            "generation_config": {
                "temperature": temperature,
                "top_p": top_p,
                "seed": 42,
                "response_mime_type": "application/json",
                "response_json_schema": gemini_json_schema,
            },
        },
    }

def build_ollama_request(prompt: str,
                         model: str,
                         temperature: float,
                         top_p: float,
                         top_k: int,
                         seed: int) -> dict[str, object]:
    schema = {
        "type": "object",
        "properties": {
            "focal_methods": {
                "type": "array",
                "items": {
                    "type": "object",
                    "required": ["test_id", "method_id", "class_name", "method_name"],
                    "properties": {
                        "test_id": {"type": "integer"},
                        "method_id": {"type": "integer"},
                        "class_name": {"type": "string"},
                        "method_name": {"type": "string"},
                    },
                },
            }
        },
        "required": ["focal_methods"],
    }
    system_text = build_system_text()
    return {
        "model": model,
        "messages": [
            {"role": "system", "content": system_text},
            {"role": "user", "content": prompt},
        ],
        "options": {
            "temperature": temperature,
            "top_p": top_p,
            "top_k": top_k,
            "seed": seed,
        },
        "format": {
            "type": "json_schema",
            "json_schema": schema,
        },
        "stream": False,
    }


def parse_args(argv: list[str]) -> dict[str, object]:
    args: dict[str, object] = {
        "input_path": "/home/agent/where/TestWhere/dbs/random-llm-sample.filtered.jsonl",
        "output_dir": "/home/agent/where/TestWhere/dbs/batch_input_400",
        "dedupe_output": None,
        "openai_models": ["gpt-5", "gpt-5-mini"],
        "gemini_models": ["gemini-3", "gemini-3-flash-preview"],
        "max_lines": None,
        "estimate_costs": False,
        "ollama_model": "qwen3.5:cloud",
        "ollama_seed": 42,
        "ollama_temperature": 0.3,
        "ollama_top_p": 0.8,
        "ollama_top_k": 40,
    }
    it = iter(argv)
    for token in it:
        if token == "--input":
            args["input_path"] = next(it)
        elif token == "--out":
            args["output_dir"] = next(it)
        elif token == "--dedupe-output":
            args["dedupe_output"] = next(it)
        elif token == "--openai-models":
            args["openai_models"] = [m.strip() for m in next(it).split(",") if m.strip()]
        elif token == "--gemini-models":
            args["gemini_models"] = [m.strip() for m in next(it).split(",") if m.strip()]
        elif token == "--n":
            args["max_lines"] = int(next(it))
        elif token == "--estimate":
            args["estimate_costs"] = True
        elif token == "--ollama-model":
            args["ollama_model"] = next(it)
        elif token == "--ollama-seed":
            args["ollama_seed"] = int(next(it))
        elif token == "--ollama-temp":
            args["ollama_temperature"] = float(next(it))
        elif token == "--ollama-top-k":
            args["ollama_top_k"] = int(next(it))
        elif token == "--ollama-top-p":
            args["ollama_top_p"] = float(next(it))
        else:
            raise ValueError(f"Unknown argument: {token}")
    return args


def estimate_tokens_for_file(path: Path, model: str) -> Optional[int]:
    try:
        import tiktoken  # type: ignore
    except Exception:
        return None
    try:
        enc = tiktoken.encoding_for_model(model)
    except Exception:
        enc = tiktoken.get_encoding("cl100k_base")

    total = 0
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            req = json.loads(line)
            body = req.get("body", {})
            input_items = body.get("input") if isinstance(body, dict) else None
            if not isinstance(input_items, list):
                continue
            parts: list[str] = []
            for item in input_items:
                if not isinstance(item, dict):
                    continue
                content = item.get("content")
                if isinstance(content, list):
                    for c in content:
                        if isinstance(c, dict) and isinstance(c.get("text"), str):
                            parts.append(c["text"])
                elif isinstance(content, str):
                    parts.append(content)
            if parts:
                total += len(enc.encode("\n".join(parts)))
    return total


def main() -> int:
    try:
        parsed = parse_args(sys.argv[1:])
    except (StopIteration, ValueError) as exc:
        print(f"Argument error: {exc}")
        print("Usage: batch_prepare_llm_requests.py "
              "[--input PATH] [--out DIR] "
              "[--openai-models m1,m2] [--gemini-models m1,m2] "
              "[--n COUNT] [--dedupe-output PATH]")
        return 1

    input_path = Path(parsed["input_path"])
    output_dir = Path(parsed["output_dir"])
    dedupe_output = parsed["dedupe_output"]
    openai_models = parsed["openai_models"]
    gemini_models = parsed["gemini_models"]
    max_lines = parsed["max_lines"]
    estimate_costs = parsed["estimate_costs"]
    ollama_model = parsed["ollama_model"]
    ollama_seed = parsed["ollama_seed"]
    ollama_temperature = parsed["ollama_temperature"]
    ollama_top_k = parsed["ollama_top_k"]
    ollama_top_p = parsed["ollama_top_p"]
    temperature = 0.1
    top_p = 0.9

    if not input_path.exists():
        print(f"Input not found: {input_path}")
        return 1

    if len(openai_models) != 2 or len(gemini_models) != 2:
        print("Expected exactly 2 OpenAI models and 2 Gemini models.")
        return 1

    output_dir.mkdir(parents=True, exist_ok=True)
    openai_outputs = {
        openai_models[0]: output_dir / "openai-batch-requests.gpt5.jsonl",
        openai_models[1]: output_dir / "openai-batch-requests.gpt5-mini.jsonl",
    }
    gemini_outputs = {
        gemini_models[0]: output_dir / "gemini-batch-requests.gemini-3.jsonl",
        gemini_models[1]: output_dir / "gemini-batch-requests.gemini-3-flash-preview.jsonl",
    }
    ollama_output = output_dir / f"ollama-requests.{model_tag(ollama_model)}.jsonl"

    openai_handles = {model: path.open("w", encoding="utf-8") for model, path in openai_outputs.items()}
    gemini_handles = {model: path.open("w", encoding="utf-8") for model, path in gemini_outputs.items()}
    ollama_handle = ollama_output.open("w", encoding="utf-8")
    human_readable_path = output_dir / "human-readable-llm-requests.txt"
    human_handle = human_readable_path.open("w", encoding="utf-8")
    duplicate_groups: dict[str, dict[str, object]] = {}
    exact_seen: set[str] = set()
    try:
        with input_path.open("r", encoding="utf-8") as handle:
            count = 0
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                record = json.loads(line)
                signature_id, method_block = build_duplicate_signature(record)
                invoked_key = normalize_invoked_methods(record.get("invoked_methods"))
                if method_block:
                    entry = duplicate_groups.setdefault(signature_id, {
                        "signature_id": signature_id,
                        "repo_name": record.get("repo_name"),
                        "test_file": record.get("test_file"),
                        "test_method": record.get("test_method"),
                        "invoked_methods_key": invoked_key,
                        "method_block": method_block,
                        "test_ids": [],
                        "test_names": [],
                    })
                    entry["test_ids"].append(record.get("test_id"))
                    entry["test_names"].append(record.get("test_name"))
                exact_key = build_exact_dedupe_key(record)
                if exact_key in exact_seen:
                    continue
                exact_seen.add(exact_key)
                prompt = build_prompt(record)
                custom_id = f"{record.get('repo_name')}::{record.get('test_id')}"

                for model, out_handle in openai_handles.items():
                    openai_req = build_openai_request(
                        prompt,
                        custom_id,
                        model,
                        temperature,
                        top_p,
                    )
                    out_handle.write(json.dumps(openai_req) + "\n")

                for model, out_handle in gemini_handles.items():
                    gemini_req = build_gemini_request(
                        prompt,
                        custom_id,
                        model,
                        temperature,
                        top_p,
                    )
                    out_handle.write(json.dumps(gemini_req) + "\n")
                ollama_req = build_ollama_request(
                    prompt,
                    ollama_model,
                    ollama_temperature,
                    ollama_top_p,
                    ollama_top_k,
                    ollama_seed,
                )
                ollama_handle.write(json.dumps(ollama_req) + "\n")
                human_handle.write(build_prompt(record))
                human_handle.write("\n" + "=" * 80 + "\n\n")
                count += 1
                if max_lines is not None and count >= max_lines:
                    break
    finally:
        for handle in openai_handles.values():
            handle.close()
        for handle in gemini_handles.values():
            handle.close()
        ollama_handle.close()
        human_handle.close()

    for path in openai_outputs.values():
        print(f"Wrote {path}")
    for path in gemini_outputs.values():
        print(f"Wrote {path}")
    print(f"Wrote {ollama_output}")
    if estimate_costs:
        for model, path in openai_outputs.items():
            tokens = estimate_tokens_for_file(path, model)
            if tokens is None:
                print(f"Token estimate skipped (tiktoken missing) for {path}")
            else:
                print(f"Estimated input tokens for {path}: {tokens}")
        print("Multiply input tokens by provider rates for cost estimate.")
    print(f"Wrote {human_readable_path}")
    if dedupe_output is None:
        dedupe_output = str(output_dir / "duplicate-test-groups.jsonl")
    dedupe_path = Path(dedupe_output)
    dedupe_path.parent.mkdir(parents=True, exist_ok=True)
    with dedupe_path.open("w", encoding="utf-8") as handle:
        for group in duplicate_groups.values():
            if len(group.get("test_ids", [])) < 2:
                continue
            handle.write(json.dumps(group) + "\n")
    print(f"Wrote {dedupe_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

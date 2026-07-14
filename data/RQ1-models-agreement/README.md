# RQ1 Models Agreement

This folder contains the script and input data used to reproduce the inter-model agreement analysis for four pilot repositories:

- `commons-io`
- `commons-lang`
- `gson`
- `spotify-web-api-java`

## Contents

- `analysis/compute_llm_jaccard.py`
  Computes per-test Jaccard agreement between Gemini, DeepSeek, and Qwen using the `focal_methods` field in each `output.compressed.jsonl` file.
- `dbs/batch_input_<repo>/.../output.compressed.jsonl`
  Model outputs copied from the original workspace.

## How To Run

From root directory, run:

```bash
python3 ./data/RQ1-models-agreement/analysis/compute_llm_jaccard.py \
  --repos commons-lang gson spotify-web-api-java commons-io
```

## Expected Combined Result

The combined pairwise per-test Jaccard values should be:

- Gemini vs DeepSeek: `0.795747`
- Gemini vs Qwen: `0.809511`
- DeepSeek vs Qwen: `0.818697`

Rounded table values:

- `0.796`
- `0.810`
- `0.819`

Combined consensus by model should be:

- Gemini: `0.802629`
- DeepSeek: `0.807222`
- Qwen: `0.814104`

## Notes For Reviewers

- The script computes agreement at the **per-test** level, not as one global set-overlap over a repository.
- For each test case, it compares the focal-method `method_id` sets selected by two models and computes Jaccard as:

```text
|intersection| / |union|
```

- It then averages those per-test Jaccard scores across all counted tests.

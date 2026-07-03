# Dynamic-Analysis Bundle

This directory contains the self-contained dynamic-analysis portion of the anonymous replication package.

## Main entrypoints

- `jacoco-pertest/jacoco-pertest-wrapper-v2.sh`
- `scripts/rebuild_repo_db.sh`
- `scripts/process_all_java_repos.sh`

## Recommended order

1. Check the environment:

```bash
./check_env.sh
```

2. Run a smoke test on one small repository:

```bash
./smoke_test.sh commons-exec
```

3. Rebuild one repository end-to-end:

```bash
./scripts/rebuild_repo_db.sh commons-io
```

## Reproducibility notes

- The scripts now default to a bundle-local Maven repository:
  - `.m2/repository`
- This avoids dependence on the reviewer's `~/.m2`.
- On first run, Maven may still need network access to resolve missing artifacts unless the local repository has already been populated.

## Documentation

- [DEPENDENCIES.md](DEPENDENCIES.md) lists the required runtime tools and bundled helper components.

## Docker

Build from this directory:

```bash
docker build -t anonymous-dynamic-analysis .
```

Run an interactive shell:

```bash
docker run --rm -it anonymous-dynamic-analysis
```

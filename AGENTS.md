# Kestra Mistral Plugin

## What

- Provides plugin components under `io.kestra.plugin.mistral`.
- Includes classes such as `ChatCompletion`.

## Why

- This plugin integrates Kestra with Mistral.
- It provides tasks that send chat completions to Mistral models.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `mistral`

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `io.kestra.plugin.mistral.ChatCompletion`

### Project Structure

```
plugin-mistral/
├── src/main/java/io/kestra/plugin/mistral/
├── src/test/java/io/kestra/plugin/mistral/
├── build.gradle
└── README.md
```

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines

# Kestra Mistral Plugin

## What

- Provides plugin components under `io.kestra.plugin.mistral`.
- Includes classes such as `ChatCompletion`.

## Why

- What user problem does this solve? Teams need to send chat completions to Mistral models from orchestrated workflows instead of relying on manual console work, ad hoc scripts, or disconnected schedulers.
- Why would a team adopt this plugin in a workflow? It keeps Mistral steps in the same Kestra flow as upstream preparation, approvals, retries, notifications, and downstream systems.
- What operational/business outcome does it enable? It reduces manual handoffs and fragmented tooling while improving reliability, traceability, and delivery speed for processes that depend on Mistral.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `mistral`

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `io.kestra.plugin.mistral.ChatCompletion`
- `io.kestra.plugin.mistral.RunWorkflow` — starts a Mistral Workflow execution (sync or fire-and-forget); polls until terminal state when `wait: true`
- `io.kestra.plugin.mistral.WorkflowEvents` — polling trigger that emits one Kestra execution per matching Mistral workflow event (cursor-based, cold-start safe)

> **Note:** Mistral Workflows is currently in public preview. Register workflows via the Mistral Python SDK or Studio before using `RunWorkflow` or `WorkflowEvents`.

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

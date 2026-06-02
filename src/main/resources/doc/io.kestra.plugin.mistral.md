# How to use the Mistral plugin

Call Mistral AI models for chat completions and orchestrate Mistral Workflow executions from Kestra flows.

## Authentication

Set `apiKey` to your Mistral API key. Store it in a [secret](https://kestra.io/docs/concepts/secret).

## Tasks

`ChatCompletion` sends a prompt to a Mistral model and returns the response — use it for text generation, summarization, and classification tasks.

`RunWorkflow` starts a Mistral Workflow execution in fire-and-forget mode by default; set `wait: true` to poll until the execution reaches a terminal state. `WorkflowEvents` is a polling trigger that starts one Kestra execution per matching Mistral workflow event, making it the right choice for event-driven pipelines that react to Mistral workflow completions. Both tasks require workflows registered via the Mistral Python SDK or Studio — Mistral Workflows is currently in public preview.

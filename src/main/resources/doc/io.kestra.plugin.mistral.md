# How to use the Mistral plugin

Call Mistral AI models for chat completions and orchestrate Mistral Workflow executions from Kestra flows.

## Authentication

Set `apiKey` to your Mistral API key. Store it in a [secret](https://kestra.io/docs/concepts/secret).

## Tasks

`ChatCompletion` sends a prompt to a Mistral model and returns the response. Use it for text generation, summarization, and classification tasks.

`RunWorkflow` starts a Mistral Workflow execution and, by default (`wait: true`), polls until it reaches a terminal state. Set `wait: false` to return as soon as the execution is started. Killing the task stops the poll loop and requests cancellation of the Mistral execution, so a killed Kestra task does not leave it running. A worker shutdown only stops the poll loop and leaves the Mistral execution alive, since the task run can be resubmitted onto another worker. A `wait: false` execution is deliberately detached and is never cancelled.

`WorkflowEvents` is a polling trigger that starts one Kestra execution per matching Mistral workflow event, for pipelines that react to Mistral workflow completions. Both require workflows registered via the Mistral Python SDK or Studio. Mistral Workflows is currently in public preview.

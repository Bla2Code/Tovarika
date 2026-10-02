# Repository security rules

- Never read, print, search, summarize, copy, upload, or otherwise transmit `.env` files or their contents.
- Treat `.env`, `.env.*`, credentials, API keys, access tokens, and private keys as sensitive data.
- Never read files used as Compose secret sources, including `openai_api_key`, paths supplied via `OPENAI_API_KEY_SOURCE`, and anything under `/run/secrets`.
- Do not include sensitive values in prompts, tool output, logs, tests, fixtures, commits, or network requests, except where application runtime authentication explicitly requires a configured secret to be sent to its intended provider.
- Use documented environment-variable names and redacted placeholders when examples are needed.

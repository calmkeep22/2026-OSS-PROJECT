# Security Policy

Please do not disclose credential leaks, order-safety bypasses, or broker authentication defects in a public issue. Report them privately to the project maintainers with reproduction steps and affected versions.

Never attach real API keys, tokens, account numbers, DPAPI payloads, or production databases. Use fake credentials and the mock-trading/fake-adapters modules for reproduction.

The project intentionally fails closed when a protected secret store is unavailable. Plaintext fallback is not a supported workaround.

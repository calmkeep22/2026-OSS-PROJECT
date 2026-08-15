# anomaly-detection

Pure rule-based market anomaly detection.

- Public API: anomaly rules, detector, finding/severity models
- Depends on: `finance-domain`
- Contains no UI notifications, broker transport, or persistence.
- Consumers decide how a finding is displayed, spoken, or stored.

Test: `./gradlew :modules:anomaly-detection:test`

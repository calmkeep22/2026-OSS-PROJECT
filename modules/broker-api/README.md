# broker-api

Broker-neutral REST boundary and resilience/security helpers.

- Public API: `BrokerClient`, credentials/token models, broker exceptions, retry policy
- Depends on: `finance-domain`
- Does not depend on application use cases or any concrete broker.
- Real-time streaming remains an application port because it has a different lifecycle.

Test: `./gradlew :modules:broker-api:test`

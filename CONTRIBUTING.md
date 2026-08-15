# Contributing

1. Open an issue using the repository template and agree on the public contract before implementation.
2. Keep dependencies pointing inward: UI/infrastructure → application → finance-domain.
3. Add or update tests for every behavior change. Adapter implementations should reuse contracts from `application` test fixtures.
4. Run `./gradlew clean test verifyModuleBoundaries` before opening a pull request.
5. Do not commit credentials, account numbers, tokens, generated databases, or local preference files.

Commits and pull requests should follow the templates already included in `.gitmessage` and `.github`.

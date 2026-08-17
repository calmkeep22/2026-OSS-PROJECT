# desktop-javafx

JavaFX presentation module and application composition root.

- `DesktopApplication` owns JavaFX lifecycle and screen navigation.
- `composition.DesktopServices` is the only class that selects concrete adapters.
- View models depend on application ports, not fake or broker implementations.
- Search, detail, chart-history, and watchlist quote refreshes use `MarketApplicationPort`
  asynchronously and apply results on the JavaFX Application Thread.
- Desktop-only preference records remain here because they are not shared domain concepts.
- The connection screen stores, reuses, and deletes environment-specific Kiwoom credentials
  through `SecretStore`; the default Windows composition uses DPAPI and never falls back to plaintext.
- Accessible chart screens depend on the core `SonificationPort`; `DesktopServices` selects the
  separate `sonification-java-sound` PCM adapter and the application owns its lifetime.

Run: `./gradlew :apps:desktop-javafx:run`

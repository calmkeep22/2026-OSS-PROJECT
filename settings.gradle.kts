rootProject.name = "2026-OSS-PROJECT"

include(
    ":apps:desktop-javafx",
    ":modules:finance-domain",
    ":modules:application",
    ":modules:mock-trading",
    ":modules:anomaly-detection",
    ":modules:accessibility",
    ":modules:sonification",
    ":modules:sonification-java-sound",
    ":modules:fake-adapters",
    ":modules:broker-api",
    ":modules:kiwoom-adapter",
    ":modules:ai-insight-api",
    ":modules:ai-insight-http",
    ":modules:persistence-sqlite",
    ":modules:secret-store-api",
    ":modules:file-secret-store",
    ":modules:windows-secret-store"
)

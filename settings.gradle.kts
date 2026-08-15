rootProject.name = "2026-OSS-PROJECT"

include(
    ":apps:desktop-javafx",
    ":modules:finance-domain",
    ":modules:application",
    ":modules:mock-trading",
    ":modules:anomaly-detection",
    ":modules:accessibility",
    ":modules:sonification",
    ":modules:fake-adapters",
    ":modules:broker-api",
    ":modules:kiwoom-adapter",
    ":modules:persistence-sqlite",
    ":modules:secret-store-api",
    ":modules:file-secret-store",
    ":modules:windows-secret-store"
)

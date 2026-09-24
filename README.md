# Tally Offline APK

Android WebView app for the Tally Customer Portal with a local Room database and offline data collector.

## Included

- Local Room database: companies, ledgers, vouchers, voucher entries, stock items, sync state.
- Fixed **SYNC TALLY DATA** button injected into authenticated Tally pages.
- Visible HTML-table collector for ledger, voucher and stock reports.
- Chunked bridge transfer for larger sync payloads.
- Incremental sync so collecting one report does not wipe other collected report data.
- Cookie persistence for the Tally Customer Portal session.

## Sync usage

Open the required Tally browser report inside the app, then press **SYNC TALLY DATA**. The collector saves only values that can be read from the visible report tables.

The collector deliberately does not generate sample/random accounting data.

See `database/SYNC_COLLECTOR.md` for details.

## Build

The repository includes a Gradle wrapper and GitHub Actions workflow. A cloud runner is recommended when the local machine cannot reach `services.gradle.org`.

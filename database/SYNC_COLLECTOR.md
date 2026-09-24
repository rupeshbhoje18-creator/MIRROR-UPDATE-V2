# Tally Offline Sync Collector

This project now includes a real WebView Sync Collector. It does **not** fabricate ledger, voucher, or stock figures.

## How it works

1. The app opens the authenticated Tally Customer Portal inside WebView.
2. A fixed **SYNC TALLY DATA** button is injected into the page.
3. When the user opens a Tally browser report and presses that button, the collector reads visible HTML tables.
4. Rows are classified as Ledger, Voucher, or Stock Item when the table headers contain the relevant fields.
5. The collector sends JSON to the Android bridge. Large payloads are split into chunks.
6. Room stores the data locally in `tally_offline_final.db`.
7. Page-level syncs are incremental: syncing one report does not delete previously collected data from other report types.

## Important limitation

The collector reads data that is actually present in the authenticated browser page. It does not guess hidden pages, bypass Tally permissions, or invent missing values. To collect different report types, open the corresponding Tally browser report and press **SYNC TALLY DATA** on that page.

A future full-sync implementation can use Tally's supported report/API responses if their exact response format is made available.

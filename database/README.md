# Tally Offline Final Database

This folder contains the final offline database schema used by the Android Room database.

Database file: `tally_offline_final.db`
Room database name: `tally_offline_final.db`
Schema version: `3`

Tables:
- `companies` — company identity and sync timestamp
- `ledgers` — ledger masters and opening/closing balances
- `vouchers` — voucher headers
- `voucher_entries` — ledger allocations inside vouchers
- `stock_items` — stock item master and closing/opening figures
- `sync_state` — per-company sync status

No sample/random accounting data is included. The database is an empty schema ready for real Tally sync payloads.

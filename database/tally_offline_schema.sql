PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS companies (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  companyName TEXT NOT NULL,
  serialNumber TEXT NOT NULL,
  gstin TEXT,
  financialYearFrom TEXT,
  lastSynced INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS index_companies_serialNumber ON companies(serialNumber);

CREATE TABLE IF NOT EXISTS ledgers (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  companyId INTEGER NOT NULL,
  guid TEXT NOT NULL,
  name TEXT NOT NULL,
  parent TEXT,
  openingBalance REAL NOT NULL DEFAULT 0,
  closingBalance REAL NOT NULL DEFAULT 0,
  alteredOn INTEGER,
  FOREIGN KEY(companyId) REFERENCES companies(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS index_ledgers_companyId ON ledgers(companyId);
CREATE UNIQUE INDEX IF NOT EXISTS index_ledgers_companyId_guid ON ledgers(companyId,guid);
CREATE INDEX IF NOT EXISTS index_ledgers_companyId_name ON ledgers(companyId,name);

CREATE TABLE IF NOT EXISTS vouchers (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  companyId INTEGER NOT NULL,
  guid TEXT NOT NULL,
  date TEXT NOT NULL,
  voucherType TEXT NOT NULL,
  voucherNumber TEXT,
  partyName TEXT,
  amount REAL NOT NULL DEFAULT 0,
  narration TEXT,
  alteredOn INTEGER,
  FOREIGN KEY(companyId) REFERENCES companies(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS index_vouchers_companyId ON vouchers(companyId);
CREATE UNIQUE INDEX IF NOT EXISTS index_vouchers_companyId_guid ON vouchers(companyId,guid);
CREATE INDEX IF NOT EXISTS index_vouchers_companyId_date ON vouchers(companyId,date);

CREATE TABLE IF NOT EXISTS voucher_entries (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  voucherId INTEGER NOT NULL,
  ledgerGuid TEXT,
  ledgerName TEXT NOT NULL,
  amount REAL NOT NULL DEFAULT 0,
  isDeemedPositive INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(voucherId) REFERENCES vouchers(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS index_voucher_entries_voucherId ON voucher_entries(voucherId);
CREATE INDEX IF NOT EXISTS index_voucher_entries_ledgerGuid ON voucher_entries(ledgerGuid);

CREATE TABLE IF NOT EXISTS stock_items (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  companyId INTEGER NOT NULL,
  guid TEXT NOT NULL,
  name TEXT NOT NULL,
  parent TEXT,
  unit TEXT,
  openingQty REAL NOT NULL DEFAULT 0,
  closingQty REAL NOT NULL DEFAULT 0,
  openingValue REAL NOT NULL DEFAULT 0,
  closingValue REAL NOT NULL DEFAULT 0,
  closingRate REAL NOT NULL DEFAULT 0,
  alteredOn INTEGER,
  FOREIGN KEY(companyId) REFERENCES companies(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS index_stock_items_companyId ON stock_items(companyId);
CREATE UNIQUE INDEX IF NOT EXISTS index_stock_items_companyId_guid ON stock_items(companyId,guid);
CREATE INDEX IF NOT EXISTS index_stock_items_companyId_name ON stock_items(companyId,name);

CREATE TABLE IF NOT EXISTS sync_state (
  companyId INTEGER PRIMARY KEY NOT NULL,
  lastAttempt INTEGER NOT NULL DEFAULT 0,
  lastSuccess INTEGER,
  status TEXT NOT NULL DEFAULT 'NEVER_SYNCED',
  message TEXT,
  FOREIGN KEY(companyId) REFERENCES companies(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS page_snapshots (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  companyId INTEGER NOT NULL,
  url TEXT NOT NULL,
  title TEXT NOT NULL,
  htmlGzip BLOB NOT NULL,
  lastCaptured INTEGER NOT NULL,
  FOREIGN KEY(companyId) REFERENCES companies(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS index_page_snapshots_companyId ON page_snapshots(companyId);
CREATE UNIQUE INDEX IF NOT EXISTS index_page_snapshots_companyId_url ON page_snapshots(companyId,url);
CREATE INDEX IF NOT EXISTS index_page_snapshots_companyId_lastCaptured ON page_snapshots(companyId,lastCaptured);

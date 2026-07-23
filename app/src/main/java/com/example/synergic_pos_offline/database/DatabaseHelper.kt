package com.example.synergic_pos_offline.database

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Central offline SQLite schema for the POS app.
 * Master tables (md_ prefix) are created before transaction tables (td_ prefix)
 * so that foreign key references resolve in dependency order.
 */
class DatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // A pending migration has to rebuild a parent table, which SQLite only
        // permits with foreign keys off - and they cannot be toggled from inside
        // onUpgrade, which already runs in a transaction. [onOpen] switches them
        // back on once any migration has finished.
        db.setForeignKeyConstraintsEnabled(db.version == DATABASE_VERSION)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // Non-destructive migrations for existing databases (no version bump / data loss).
        addColumnIfMissing(db, Tables.MD_APP_SETTINGS, "device_id", "TEXT")
    }

    /**
     * Physically re-writes [Tables.MD_APP_SETTINGS] so its rows are stored grouped
     * by setting_type (then setting_name). New auto ids are assigned in that order,
     * so a plain `SELECT *` (e.g. in the DB Inspector) shows the rows grouped.
     */
    fun regroupAppSettingsByType() {
        val db = writableDatabase
        val table = Tables.MD_APP_SETTINGS
        db.beginTransaction()
        try {
            val rows = mutableListOf<ContentValues>()
            db.query(table, null, null, null, null, null, "setting_type ASC, setting_name ASC").use { c ->
                while (c.moveToNext()) {
                    val cv = ContentValues()
                    android.database.DatabaseUtils.cursorRowToContentValues(c, cv)
                    cv.remove("id")   // let AUTOINCREMENT assign fresh ids in order
                    rows.add(cv)
                }
            }
            if (rows.isEmpty()) { db.setTransactionSuccessful(); return }
            db.delete(table, null, null)
            db.execSQL("DELETE FROM sqlite_sequence WHERE name = ?", arrayOf(table))
            rows.forEach { db.insert(table, null, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Adds [column] to [table] if it isn't already present, leaving data intact. */
    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, type: String) {
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            generateSequence { if (c.moveToNext()) c.getString(nameIdx) else null }.any { it == column }
        }
        if (!exists) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Master tables
        db.execSQL(SQL_CREATE_MD_REGISTRATION)
        db.execSQL(SQL_CREATE_MD_USERS)
        db.execSQL(SQL_CREATE_MD_CATEGORY)
        db.execSQL(SQL_CREATE_MD_UNITS)
        db.execSQL(SQL_CREATE_MD_PRODUCTS)
        db.execSQL(SQL_CREATE_MD_PRODUCT_RATES)
        db.execSQL(SQL_CREATE_MD_CUSTOMERS)
        db.execSQL(SQL_CREATE_MD_DESCRIPTION)
        db.execSQL(SQL_CREATE_MD_WAITERS)
        db.execSQL(SQL_CREATE_MD_HEADERS)
        db.execSQL(SQL_CREATE_MD_FOOTERS)
        db.execSQL(SQL_CREATE_MD_LOGOS)
        db.execSQL(SQL_CREATE_MD_QR)
        db.execSQL(SQL_CREATE_MD_APP_SETTINGS)
        db.execSQL(SQL_CREATE_MD_SUPPLIER)
        db.execSQL(SQL_CREATE_MD_BATCH_STOCK)
        db.execSQL(SQL_CREATE_MD_VERSION)
        db.execSQL(SQL_CREATE_MD_PRINTER)
        seedDefaultPrinters(db)
        db.execSQL("UPDATE ${Tables.MD_PRINTER} SET is_selected = 1")
        addExtraPrinterTypes(db)
        db.execSQL(SQL_CREATE_MD_OPERATING_PRINTER)

        // Transaction tables
        db.execSQL(SQL_CREATE_TD_PURCHASE)
        db.execSQL(SQL_CREATE_TD_PURCHASE_RETURN)
        db.execSQL(SQL_CREATE_TD_WRITE_OFF)
        db.execSQL(SQL_CREATE_TD_BILLS)
        db.execSQL(SQL_CREATE_TD_BILL_ITEMS)
        db.execSQL(SQL_CREATE_TD_PAYMENTS)
        db.execSQL(SQL_CREATE_TD_SALE_RETURNS)
        db.execSQL(SQL_CREATE_TD_RETURN_ITEMS)
        db.execSQL(SQL_CREATE_TD_STOCK_TRANSACTIONS)
        db.execSQL(SQL_CREATE_TD_CUSTOMER_LEDGER)
        db.execSQL(SQL_CREATE_TD_ADVANCE_PAYMENTS)
        db.execSQL(SQL_CREATE_TD_KOT)
        db.execSQL(SQL_CREATE_TD_KOT_ITEMS)
        db.execSQL(SQL_CREATE_TD_BILL_PRINTS)

        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrations are cumulative, so an install several versions behind runs each
        // in turn. They all preserve data: a blanket drop would take md_users and
        // md_registration with it and lock the operator out of the app.
        if (oldVersion in 1 until DATABASE_VERSION) {
            if (oldVersion < 2) migrateV2AllowCardBillType(db)
            if (oldVersion < 3) migrateV3ProductGstSlab(db)
            if (oldVersion < 4) migrateV4AllowCreditPaymentMode(db)
            if (oldVersion < 5) migrateV5RecordBalanceDue(db)
            if (oldVersion < 6) migrateV6AddPrinterTable(db)
            if (oldVersion < 7) migrateV7AddPrinterIp(db)
            if (oldVersion < 8) migrateV8ImportSavedPrinterIp(db)
            if (oldVersion < 9) migrateV9AddPaperWidth(db)
            if (oldVersion < 10) migrateV10AddPrinterTypes(db)
            if (oldVersion < 11) migrateV11AddOperatingPrinterTable(db)
            if (oldVersion < 12) migrateV12AddOperatingPrinterDefaultFlag(db)
            if (oldVersion < 13) migrateV13AddOperatingPrinterPaperWidth(db)
            return
        }

        // Unrecognised starting point: fall back to recreating the schema.
        for (table in ALL_TABLES.asReversed()) {
            db.execSQL("DROP TABLE IF EXISTS $table")
        }
        onCreate(db)
    }

    /**
     * v6: adds the md_printer lookup, mapping each print purpose (BILL/KOT/OTHERS)
     * to its connection type. Created and seeded here for existing databases; fresh
     * installs get the same from onCreate.
     */
    private fun migrateV6AddPrinterTable(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_MD_PRINTER)
        seedDefaultPrinters(db)
    }

    /** v7: holds each printer's saved address (WIFI/LAN IP). Null until configured. */
    private fun migrateV7AddPrinterIp(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE ${Tables.MD_PRINTER} ADD COLUMN printer_ip TEXT")
    }

    /**
     * v8: brings the WiFi printer address saved by the old settings flow
     * (md_app_settings.printer_wifi_ip) into md_printer, against the BILL slot - the
     * purpose that printer was used for. Only fills an empty slot, so an address
     * already set through the new Printer Settings screen is left untouched.
     */
    private fun migrateV8ImportSavedPrinterIp(db: SQLiteDatabase) {
        val savedIp = db.query(
            Tables.MD_APP_SETTINGS, arrayOf("setting_value"),
            "setting_name = ?", arrayOf("printer_wifi_ip"), null, null, "id DESC", "1"
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

        if (savedIp.isNullOrBlank()) return

        db.execSQL(
            "UPDATE ${Tables.MD_PRINTER} SET printer_ip = ? " +
                "WHERE printer_purpose = 'BILL' AND (printer_ip IS NULL OR printer_ip = '')",
            arrayOf(savedIp)
        )
    }

    /**
     * v9: adds paper_mm (58 or 80) to md_printer and brings across the paper width
     * saved by the old settings flow (md_app_settings.printer_paper_width_mm) for the
     * BILL slot. Only fills an empty value.
     */
    private fun migrateV9AddPaperWidth(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE ${Tables.MD_PRINTER} ADD COLUMN paper_mm INTEGER")

        val savedPaper = db.query(
            Tables.MD_APP_SETTINGS, arrayOf("setting_value"),
            "setting_name = ?", arrayOf("printer_paper_width_mm"), null, null, "id DESC", "1"
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }?.toIntOrNull()

        if (savedPaper != null) {
            db.execSQL(
                "UPDATE ${Tables.MD_PRINTER} SET paper_mm = ? " +
                    "WHERE printer_purpose = 'BILL' AND paper_mm IS NULL",
                arrayOf(savedPaper.toString())
            )
        }
    }

    /**
     * v10: each purpose can now be connected several ways. Adds is_selected (the
     * connection currently chosen for a purpose), normalises the old 'BT' label to
     * 'BLUETOOTH', keeps whatever single row each purpose already had as its selected
     * choice, and adds BLUETOOTH and USB options alongside it.
     */
    private fun migrateV10AddPrinterTypes(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE ${Tables.MD_PRINTER} ADD COLUMN is_selected INTEGER DEFAULT 0")
        db.execSQL("UPDATE ${Tables.MD_PRINTER} SET printer_type = 'BLUETOOTH' WHERE printer_type = 'BT'")
        // The rows already present are the one-per-purpose choices - keep them selected.
        db.execSQL("UPDATE ${Tables.MD_PRINTER} SET is_selected = 1")
        addExtraPrinterTypes(db)
    }

    /** v11: adds md_operating_printer. */
    private fun migrateV11AddOperatingPrinterTable(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_MD_OPERATING_PRINTER)
    }

    /** v12: adds default_flag to md_operating_printer, marking the default printer. */
    private fun migrateV12AddOperatingPrinterDefaultFlag(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE ${Tables.MD_OPERATING_PRINTER} ADD COLUMN default_flag INTEGER DEFAULT 0")
    }

    /**
     * v13: adds paper_mm to md_operating_printer - 58 (2 inch) or 80 (3 inch) -
     * so each named printer carries its own paper width instead of borrowing
     * whatever md_printer's connection row happens to have.
     */
    private fun migrateV13AddOperatingPrinterPaperWidth(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE ${Tables.MD_OPERATING_PRINTER} ADD COLUMN paper_mm INTEGER DEFAULT 80")
    }

    /** Ensures every purpose has a BLUETOOTH and a USB option (unselected). */
    private fun addExtraPrinterTypes(db: SQLiteDatabase) {
        for (purpose in listOf("BILL", "KOT", "OTHERS")) {
            for (type in listOf("BLUETOOTH", "USB")) {
                db.execSQL(
                    "INSERT INTO ${Tables.MD_PRINTER} (printer_purpose, printer_type, is_selected) " +
                        "SELECT ?, ?, 0 WHERE NOT EXISTS (" +
                        "SELECT 1 FROM ${Tables.MD_PRINTER} WHERE printer_purpose = ? AND printer_type = ?)",
                    arrayOf(purpose, type, purpose, type)
                )
            }
        }
    }

    /** The default purpose-to-type printer rows. Idempotent via the sl_no primary key. */
    private fun seedDefaultPrinters(db: SQLiteDatabase) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO ${Tables.MD_PRINTER} (sl_no, printer_purpose, printer_type)
            VALUES (1, 'BILL', 'WIFI'), (2, 'KOT', 'LAN'), (3, 'OTHERS', 'LAN')
            """.trimIndent()
        )
    }

    /**
     * Records what is still owed on a bill, so it can be chased later.
     *
     * Adds a PARTIAL payment status - previously a customer who paid some of a
     * credit bill was indistinguishable from one who paid none - and a
     * `balance_amount` holding the shortfall on each bill. Outstanding bills are
     * then written into td_customer_ledger as DEBIT entries with a running balance,
     * and each customer's `balance_amount` is set to what they owe in total.
     *
     * This also re-runs the payment_status repair from v4. That statement was added
     * to [migrateV4AllowCreditPaymentMode] after v4 had already been applied, so any
     * database that upgraded in between never saw it.
     */
    private fun migrateV5RecordBalanceDue(db: SQLiteDatabase) {
        // Rebuilt rather than altered: payment_status gains a value, and a CHECK
        // cannot be changed in place. Relies on foreign keys being off for the
        // upgrade (see [onConfigure]) - td_customer_ledger references td_payments.
        db.execSQL(
            """
            CREATE TABLE td_payments_v5 (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                receipt_no INTEGER,
                bill_id INTEGER,
                payment_mode TEXT CHECK(payment_mode IN ('CASH','UPI','CARD','CHEQUE','ONLINE','CREDIT')),
                amount_paid REAL DEFAULT 0,
                change_amount REAL DEFAULT 0,
                upi_transaction_id TEXT,
                card_last_four TEXT,
                cheque_number TEXT,
                payment_status TEXT CHECK(payment_status IN ('PENDING','PARTIAL','COMPLETED','FAILED')) DEFAULT 'PENDING',
                balance_amount REAL DEFAULT 0,
                payment_date TEXT,
                cust_name TEXT,
                cust_gstin TEXT,
                cust_phone TEXT,
                cust_id INTEGER,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(bill_id) REFERENCES td_bills(receipt_no),
                FOREIGN KEY(cust_id) REFERENCES md_customers(id)
            )
            """
        )
        // balance_amount is new, so the columns are listed rather than SELECT *.
        db.execSQL(
            """
            INSERT INTO td_payments_v5 (
                id, receipt_no, bill_id, payment_mode, amount_paid, change_amount,
                upi_transaction_id, card_last_four, cheque_number, payment_status,
                payment_date, cust_name, cust_gstin, cust_phone, cust_id,
                created_at, created_by, modified_by
            )
            SELECT id, receipt_no, bill_id, payment_mode, amount_paid, change_amount,
                   upi_transaction_id, card_last_four, cheque_number, payment_status,
                   payment_date, cust_name, cust_gstin, cust_phone, cust_id,
                   created_at, created_by, modified_by
            FROM ${Tables.TD_PAYMENTS}
            """.trimIndent()
        )
        db.execSQL("DROP TABLE ${Tables.TD_PAYMENTS}")
        db.execSQL("ALTER TABLE td_payments_v5 RENAME TO ${Tables.TD_PAYMENTS}")

        // What each bill still owes. Change is not deducted: the customer handing
        // over more than the total settles it in full.
        db.execSQL(
            """
            UPDATE ${Tables.TD_PAYMENTS} SET balance_amount = MAX(
                COALESCE((SELECT b.net_amount FROM ${Tables.TD_BILLS} b
                          WHERE b.receipt_no = ${Tables.TD_PAYMENTS}.bill_id), 0)
                - amount_paid, 0)
            """.trimIndent()
        )

        // Restate the status from what was actually collected.
        db.execSQL(
            """
            UPDATE ${Tables.TD_PAYMENTS} SET payment_status = CASE
                WHEN balance_amount <= 0.001 THEN 'COMPLETED'
                WHEN amount_paid > 0.001     THEN 'PARTIAL'
                ELSE 'PENDING'
            END
            WHERE payment_status <> 'FAILED'
            """.trimIndent()
        )

        // Ledger the outstanding bills so they can be recovered. The running balance
        // is summed per customer up to each bill; a correlated subquery rather than a
        // window function, which SQLite only gained after this app's minimum API.
        db.execSQL(
            """
            INSERT INTO ${Tables.TD_CUSTOMER_LEDGER} (
                customer_id, bill_id, payment_id, transaction_type, amount, balance,
                transaction_date, created_by
            )
            SELECT b.customer_id, b.receipt_no, p.id, 'DEBIT', p.balance_amount,
                   (SELECT SUM(p2.balance_amount)
                      FROM ${Tables.TD_PAYMENTS} p2
                      JOIN ${Tables.TD_BILLS} b2 ON b2.receipt_no = p2.bill_id
                     WHERE b2.customer_id = b.customer_id
                       AND b2.receipt_no <= b.receipt_no
                       AND p2.balance_amount > 0.001),
                   COALESCE(b.bill_date_time, b.bill_date),
                   'MIGRATION'
            FROM ${Tables.TD_BILLS} b
            JOIN ${Tables.TD_PAYMENTS} p ON p.bill_id = b.receipt_no
            WHERE b.customer_id IS NOT NULL
              AND p.balance_amount > 0.001
              AND NOT EXISTS (
                  SELECT 1 FROM ${Tables.TD_CUSTOMER_LEDGER} l WHERE l.bill_id = b.receipt_no
              )
            """.trimIndent()
        )

        // And what each customer owes in total.
        db.execSQL(
            """
            UPDATE ${Tables.MD_CUSTOMERS} SET balance_amount = COALESCE((
                SELECT SUM(p.balance_amount)
                FROM ${Tables.TD_PAYMENTS} p
                JOIN ${Tables.TD_BILLS} b ON b.receipt_no = p.bill_id
                WHERE b.customer_id = ${Tables.MD_CUSTOMERS}.id
                  AND p.balance_amount > 0.001
            ), 0)
            """.trimIndent()
        )
        createIndexes(db)   // the old table's indexes went with it
    }

    /**
     * Adds 'CREDIT' to the `payment_mode` CHECK, and repairs the payment rows that
     * were written before credit could be recorded honestly.
     *
     * Two corrections, both reading from figures already stored on the bill rather
     * than guessing: a credit sale had nowhere legal to record itself so it was
     * written as CASH, and every payment was stamped COMPLETED even when nothing
     * was collected.
     */
    private fun migrateV4AllowCreditPaymentMode(db: SQLiteDatabase) {
        // Same rebuild as v2: a CHECK cannot be altered in place. Relies on foreign
        // keys being off for the upgrade (see [onConfigure]) - td_customer_ledger
        // references td_payments, so the old table cannot be dropped with them on.
        db.execSQL(
            """
            CREATE TABLE td_payments_v4 (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                receipt_no INTEGER,
                bill_id INTEGER,
                payment_mode TEXT CHECK(payment_mode IN ('CASH','UPI','CARD','CHEQUE','ONLINE','CREDIT')),
                amount_paid REAL DEFAULT 0,
                change_amount REAL DEFAULT 0,
                upi_transaction_id TEXT,
                card_last_four TEXT,
                cheque_number TEXT,
                payment_status TEXT CHECK(payment_status IN ('PENDING','COMPLETED','FAILED')) DEFAULT 'PENDING',
                payment_date TEXT,
                cust_name TEXT,
                cust_gstin TEXT,
                cust_phone TEXT,
                cust_id INTEGER,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(bill_id) REFERENCES td_bills(receipt_no),
                FOREIGN KEY(cust_id) REFERENCES md_customers(id)
            )
            """
        )
        db.execSQL("INSERT INTO td_payments_v4 SELECT * FROM ${Tables.TD_PAYMENTS}")
        db.execSQL("DROP TABLE ${Tables.TD_PAYMENTS}")
        db.execSQL("ALTER TABLE td_payments_v4 RENAME TO ${Tables.TD_PAYMENTS}")

        // Re-label the credit sales that had to masquerade as cash.
        db.execSQL(
            """
            UPDATE ${Tables.TD_PAYMENTS} SET payment_mode = 'CREDIT'
            WHERE payment_mode = 'CASH' AND bill_id IN (
                SELECT receipt_no FROM ${Tables.TD_BILLS} WHERE bill_type = 'CREDIT'
            )
            """.trimIndent()
        )

        // Every payment used to be stamped COMPLETED regardless of what was taken.
        // Anything that does not cover its bill is money still owed, not settled.
        db.execSQL(
            """
            UPDATE ${Tables.TD_PAYMENTS} SET payment_status = 'PENDING'
            WHERE payment_status = 'COMPLETED' AND amount_paid + 0.001 < (
                SELECT b.net_amount FROM ${Tables.TD_BILLS} b
                WHERE b.receipt_no = ${Tables.TD_PAYMENTS}.bill_id
            )
            """.trimIndent()
        )
        createIndexes(db)   // the old table's indexes went with it
    }

    /**
     * Gives md_products a single `gst_rate` holding the slab the product is taxed
     * at, from which CGST and SGST are each derived as half.
     *
     * Adding a column is an in-place change, so unlike [migrateV2AllowCardBillType]
     * this needs no table rebuild. Existing products are back-filled from the rates
     * already captured against them, but only where CGST+SGST lands on a legal slab
     * - anything else would be rejected by the new CHECK, so it starts at 0 and has
     * to be set deliberately.
     */
    private fun migrateV3ProductGstSlab(db: SQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE ${Tables.MD_PRODUCTS} ADD COLUMN gst_rate REAL DEFAULT 0 " +
                "CHECK(gst_rate IN ($GST_SLABS_SQL))"
        )
        db.execSQL(
            """
            UPDATE ${Tables.MD_PRODUCTS} SET gst_rate = COALESCE((
                SELECT r.cgst_rate + r.sgst_rate
                FROM ${Tables.MD_PRODUCT_RATES} r
                WHERE r.product_id = ${Tables.MD_PRODUCTS}.id
                  AND (r.cgst_rate + r.sgst_rate) IN ($GST_SLABS_SQL)
                LIMIT 1
            ), 0)
            """.trimIndent()
        )
    }

    /**
     * Adds 'CARD' to the `bill_type` CHECK constraint. SQLite cannot alter a CHECK
     * in place, so the table is rebuilt and the rows copied across.
     *
     * The DDL below is deliberately a frozen copy of the v2 schema rather than a
     * reference to [SQL_CREATE_TD_BILLS] - a migration has to keep describing the
     * shape the table had at *this* version, even after the live schema moves on.
     */
    private fun migrateV2AllowCardBillType(db: SQLiteDatabase) {
        // Relies on foreign keys being off for the upgrade (see [onConfigure]):
        // dropping the old parent would otherwise fail against the child rows in
        // td_bill_items / td_payments.
        db.execSQL(
            """
            CREATE TABLE td_bills_v2 (
                receipt_no INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                outlet_id INTEGER,
                bill_number TEXT,
                bill_date TEXT,
                bill_date_time TEXT,
                customer_id INTEGER,
                operator_id INTEGER,
                waiter_id INTEGER,
                bill_type TEXT CHECK(bill_type IN ('CASH','CREDIT','CARD','ONLINE','VOID')),
                tot_price REAL DEFAULT 0,
                tot_discount_amount REAL DEFAULT 0,
                tot_discount_percentage REAL DEFAULT 0,
                discount_flag INTEGER NOT NULL DEFAULT 0,
                discount_type TEXT,
                tot_cgst_amount REAL DEFAULT 0,
                tot_sgst_amount REAL DEFAULT 0,
                tot_igst_amount REAL DEFAULT 0,
                tot_vat_amount REAL DEFAULT 0,
                tot_other_charges_amount REAL DEFAULT 0,
                tot_round_off_amount REAL DEFAULT 0,
                net_amount REAL DEFAULT 0,
                amount_in_words TEXT,
                gst_flag INTEGER NOT NULL DEFAULT 0,
                vat_flag INTEGER NOT NULL DEFAULT 0,
                is_mrp_billing INTEGER NOT NULL DEFAULT 0,
                is_return_bill INTEGER NOT NULL DEFAULT 0,
                is_duplicate INTEGER NOT NULL DEFAULT 0,
                is_voided INTEGER NOT NULL DEFAULT 0,
                bill_status TEXT CHECK(bill_status IN ('DRAFT','COMPLETED','CANCELLED')) DEFAULT 'DRAFT',
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(customer_id) REFERENCES md_customers(id),
                FOREIGN KEY(operator_id) REFERENCES md_users(id),
                FOREIGN KEY(waiter_id) REFERENCES md_waiters(id)
            )
            """
        )
        // v1 and v2 have identical columns in identical order - only the CHECK moved.
        db.execSQL("INSERT INTO td_bills_v2 SELECT * FROM td_bills")
        db.execSQL("DROP TABLE td_bills")
        db.execSQL("ALTER TABLE td_bills_v2 RENAME TO td_bills")
        createIndexes(db)   // the old table's indexes went with it
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_md_products_category ON md_products(category_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_md_products_store ON md_products(store_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_md_product_rates_product ON md_product_rates(product_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_md_batch_stock_product ON md_batch_stock(product_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_td_purchase_product ON td_purchase(product_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_td_purchase_supplier ON td_purchase(supp_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_td_bills_store_outlet ON td_bills(store_id, outlet_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_td_bills_customer ON td_bills(customer_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_td_bill_items_bill ON td_bill_items(bill_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_td_bill_items_product ON td_bill_items(product_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_td_payments_bill ON td_payments(bill_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_td_stock_transactions_product ON td_stock_transactions(product_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_td_customer_ledger_customer ON td_customer_ledger(customer_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_td_kot_bill ON td_kot(bill_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_td_kot_items_kot ON td_kot_items(kot_id)")
    }

    /** Table names, in FK-safe creation order. */
    object Tables {
        const val MD_REGISTRATION = "md_registration"
        const val MD_USERS = "md_users"
        const val MD_CATEGORY = "md_category"
        const val MD_UNITS = "md_units"
        const val MD_PRODUCTS = "md_products"
        const val MD_PRODUCT_RATES = "md_product_rates"
        const val MD_CUSTOMERS = "md_customers"
        const val MD_DESCRIPTION = "md_description"
        const val MD_WAITERS = "md_waiters"
        const val MD_HEADERS = "md_headers"
        const val MD_FOOTERS = "md_footers"
        const val MD_LOGOS = "md_logos"
        const val MD_QR = "md_qr"
        const val MD_APP_SETTINGS = "md_app_settings"
        const val MD_SUPPLIER = "md_supplier"
        const val MD_BATCH_STOCK = "md_batch_stock"
        const val MD_VERSION = "md_version"
        const val MD_PRINTER = "md_printer"
        const val MD_OPERATING_PRINTER = "md_operating_printer"

        const val TD_PURCHASE = "td_purchase"
        const val TD_PURCHASE_RETURN = "td_purchase_return"
        const val TD_WRITE_OFF = "td_write_off"
        const val TD_BILLS = "td_bills"
        const val TD_BILL_ITEMS = "td_bill_items"
        const val TD_PAYMENTS = "td_payments"
        const val TD_SALE_RETURNS = "td_sale_returns"
        const val TD_RETURN_ITEMS = "td_return_items"
        const val TD_STOCK_TRANSACTIONS = "td_stock_transactions"
        const val TD_CUSTOMER_LEDGER = "td_customer_ledger"
        const val TD_ADVANCE_PAYMENTS = "td_advance_payments"
        const val TD_KOT = "td_kot"
        const val TD_KOT_ITEMS = "td_kot_items"
        const val TD_BILL_PRINTS = "td_bill_prints"
    }

    companion object {
        private const val DATABASE_NAME = "synergic_pos.db"
        private const val DATABASE_VERSION = 6
        // v6 adds md_printer; v7 adds its printer_ip column; v8 imports the printer
        // address saved by the old settings flow; v9 adds paper_mm and imports the
        // saved paper width; v10 adds is_selected and BLUETOOTH/USB rows per purpose;
        // v11 adds md_operating_printer; v12 adds its default_flag column; v13 adds
        // its own paper_mm column (58/80, independent of md_printer's).
        private const val DATABASE_VERSION = 13

        /**
         * The GST slabs a product may be taxed at. CGST and SGST are always half of
         * the chosen slab each, so only this one figure is captured per product.
         */
        val GST_SLABS = listOf(0.0, 0.25, 3.0, 5.0, 12.0, 18.0, 28.0)

        /** The slab list as a SQL `IN (...)` body, so schema and UI cannot diverge. */
        private val GST_SLABS_SQL = GST_SLABS.joinToString(",") {
            if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
        }

        @Volatile
        private var instance: DatabaseHelper? = null

        fun getInstance(context: Context): DatabaseHelper =
            instance ?: synchronized(this) {
                instance ?: DatabaseHelper(context).also { instance = it }
            }

        private val ALL_TABLES = listOf(
            Tables.MD_REGISTRATION, Tables.MD_USERS, Tables.MD_CATEGORY, Tables.MD_UNITS,
            Tables.MD_PRODUCTS, Tables.MD_PRODUCT_RATES, Tables.MD_CUSTOMERS, Tables.MD_DESCRIPTION,
            Tables.MD_WAITERS, Tables.MD_HEADERS, Tables.MD_FOOTERS, Tables.MD_LOGOS, Tables.MD_QR,
            Tables.MD_APP_SETTINGS, Tables.MD_SUPPLIER, Tables.MD_BATCH_STOCK, Tables.MD_VERSION,
            Tables.MD_PRINTER, Tables.MD_OPERATING_PRINTER,
            Tables.TD_PURCHASE, Tables.TD_PURCHASE_RETURN, Tables.TD_WRITE_OFF, Tables.TD_BILLS,
            Tables.TD_BILL_ITEMS, Tables.TD_PAYMENTS, Tables.TD_SALE_RETURNS, Tables.TD_RETURN_ITEMS,
            Tables.TD_STOCK_TRANSACTIONS, Tables.TD_CUSTOMER_LEDGER, Tables.TD_ADVANCE_PAYMENTS,
            Tables.TD_KOT, Tables.TD_KOT_ITEMS, Tables.TD_BILL_PRINTS
        )

        // ---------------------------------------------------------------
        // Master tables (md_)
        // ---------------------------------------------------------------

        private const val SQL_CREATE_MD_REGISTRATION = """
            CREATE TABLE IF NOT EXISTS md_registration (
                store_id INTEGER PRIMARY KEY AUTOINCREMENT,
                outlet_id INTEGER,
                store_name TEXT,
                address TEXT,
                phone_no TEXT,
                store_gstin TEXT,
                device_id TEXT,
                registration_dt TEXT,
                registration_upto TEXT,
                verify_flag INTEGER NOT NULL DEFAULT 0,
                verified_by TEXT,
                verified_at TEXT
            )
        """

        private const val SQL_CREATE_MD_USERS = """
            CREATE TABLE IF NOT EXISTS md_users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                outlet_id INTEGER,
                user_id TEXT UNIQUE,
                password TEXT,
                user_name TEXT,
                phone_no TEXT,
                role TEXT CHECK(role IN ('S','A','G')),
                is_blocked INTEGER NOT NULL DEFAULT 0,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(store_id) REFERENCES md_registration(store_id)
            )
        """

        private const val SQL_CREATE_MD_CATEGORY = """
            CREATE TABLE IF NOT EXISTS md_category (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                category_name TEXT,
                category_image BLOB,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT
            )
        """

        private const val SQL_CREATE_MD_UNITS = """
            CREATE TABLE IF NOT EXISTS md_units (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                unit_name TEXT,
                unit_symbol TEXT,
                fraction_flag INTEGER NOT NULL DEFAULT 0,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT
            )
        """

        private val SQL_CREATE_MD_PRODUCTS = """
            CREATE TABLE IF NOT EXISTS md_products (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                product_name TEXT,
                hsn_code TEXT,
                stock_alert_qty REAL DEFAULT 0,
                bar_code TEXT,
                category_id INTEGER,
                gst_rate REAL DEFAULT 0 CHECK(gst_rate IN ($GST_SLABS_SQL)),
                product_image BLOB,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(category_id) REFERENCES md_category(id)
            )
        """

        // Note: product_id was not explicitly listed in the spec but is required to link
        // a product's up-to-3 rates/units back to md_products.
        private const val SQL_CREATE_MD_PRODUCT_RATES = """
            CREATE TABLE IF NOT EXISTS md_product_rates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                outlet_id INTEGER,
                product_id INTEGER,
                rate_1 REAL,
                rate_2 REAL,
                rate_3 REAL,
                unit_1_id INTEGER,
                unit_2_id INTEGER,
                unit_3_id INTEGER,
                cgst_rate REAL DEFAULT 0,
                sgst_rate REAL DEFAULT 0,
                igst_rate REAL DEFAULT 0,
                vat_rate REAL DEFAULT 0,
                discount REAL DEFAULT 0,
                discount_type TEXT CHECK(discount_type IN ('P','A')),
                batch_no TEXT,
                sell_price REAL,
                purchase_price REAL,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(product_id) REFERENCES md_products(id),
                FOREIGN KEY(unit_1_id) REFERENCES md_units(id),
                FOREIGN KEY(unit_2_id) REFERENCES md_units(id),
                FOREIGN KEY(unit_3_id) REFERENCES md_units(id)
            )
        """

        private const val SQL_CREATE_MD_CUSTOMERS = """
            CREATE TABLE IF NOT EXISTS md_customers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                outlet_id INTEGER,
                customer_name TEXT,
                customer_address TEXT,
                phone_number TEXT,
                customer_type TEXT CHECK(customer_type IN ('N','R','H')) DEFAULT 'N',
                dob TEXT,
                dom TEXT,
                gstin TEXT,
                credit_enabled INTEGER NOT NULL DEFAULT 0,
                credit_limit REAL DEFAULT 0,
                credit_days INTEGER DEFAULT 0,
                balance_amount REAL DEFAULT 0,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT
            )
        """

        private const val SQL_CREATE_MD_DESCRIPTION = """
            CREATE TABLE IF NOT EXISTS md_description (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                outlet_id INTEGER,
                description_name TEXT,
                description_type TEXT CHECK(description_type IN ('RECEIPT','PAYMENT')),
                description_id_auto TEXT,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT
            )
        """

        private const val SQL_CREATE_MD_WAITERS = """
            CREATE TABLE IF NOT EXISTS md_waiters (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                outlet_id INTEGER,
                waiter_name TEXT,
                table_no_from TEXT,
                table_no_to TEXT,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT
            )
        """

        private const val SQL_CREATE_MD_HEADERS = """
            CREATE TABLE IF NOT EXISTS md_headers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                header_number INTEGER CHECK(header_number BETWEEN 1 AND 10),
                header_text TEXT,
                font_size TEXT CHECK(font_size IN ('SMALL','MEDIUM','BIG','EXTRA_LARGE')),
                is_bold INTEGER NOT NULL DEFAULT 0,
                is_enabled INTEGER NOT NULL DEFAULT 1,
                header_type TEXT CHECK(header_type IN ('BILL','KOT'))
            )
        """

        private const val SQL_CREATE_MD_FOOTERS = """
            CREATE TABLE IF NOT EXISTS md_footers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                footer_number INTEGER CHECK(footer_number BETWEEN 1 AND 10),
                footer_text TEXT,
                font_size TEXT CHECK(font_size IN ('SMALL','MEDIUM','BIG','EXTRA_LARGE')),
                is_bold INTEGER NOT NULL DEFAULT 0,
                is_enabled INTEGER NOT NULL DEFAULT 1,
                footer_type TEXT CHECK(footer_type IN ('BILL','KOT'))
            )
        """

        private const val SQL_CREATE_MD_LOGOS = """
            CREATE TABLE IF NOT EXISTS md_logos (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                logo_type TEXT CHECK(logo_type IN ('BILL_HEADER','BILL_FOOTER','KOT_HEADER','KOT_FOOTER')),
                logo_image BLOB,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT
            )
        """

        private const val SQL_CREATE_MD_QR = """
            CREATE TABLE IF NOT EXISTS md_qr (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                outlet_id INTEGER,
                qr_code TEXT,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT
            )
        """

        private const val SQL_CREATE_MD_APP_SETTINGS = """
            CREATE TABLE IF NOT EXISTS md_app_settings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                outlet_id INTEGER,
                device_id TEXT,
                setting_name TEXT,
                setting_value TEXT,
                setting_type TEXT CHECK(setting_type IN ('G','B','T','I','A')),
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT
            )
        """

        private const val SQL_CREATE_MD_SUPPLIER = """
            CREATE TABLE IF NOT EXISTS md_supplier (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                sup_name TEXT,
                contact_person TEXT,
                sup_phone TEXT,
                sup_addr TEXT,
                sup_gstin TEXT,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT
            )
        """

        private const val SQL_CREATE_MD_BATCH_STOCK = """
            CREATE TABLE IF NOT EXISTS md_batch_stock (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                outlet_id INTEGER,
                product_id INTEGER,
                batch_no TEXT,
                expiry_date TEXT,
                current_quantity REAL DEFAULT 0,
                last_stock_update TEXT,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(product_id) REFERENCES md_products(id)
            )
        """

        private const val SQL_CREATE_MD_VERSION = """
            CREATE TABLE IF NOT EXISTS md_version (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                version TEXT,
                last_updated_on TEXT
            )
        """

        private const val SQL_CREATE_MD_PRINTER = """
            CREATE TABLE IF NOT EXISTS md_printer (
                sl_no INTEGER PRIMARY KEY,
                printer_purpose TEXT,
                printer_type TEXT,
                printer_ip TEXT,
                paper_mm INTEGER,
                is_selected INTEGER DEFAULT 0
            )
        """

        private const val SQL_CREATE_MD_OPERATING_PRINTER = """
            CREATE TABLE IF NOT EXISTS md_operating_printer (
                sl_no INTEGER PRIMARY KEY,
                printer_name TEXT,
                printer TEXT,
                value TEXT,
                print_flag INTEGER DEFAULT 0,
                default_flag INTEGER DEFAULT 0,
                paper_mm INTEGER DEFAULT 80
            )
        """

        // ---------------------------------------------------------------
        // Transaction tables (td_)
        // ---------------------------------------------------------------

        private const val SQL_CREATE_TD_PURCHASE = """
            CREATE TABLE IF NOT EXISTS td_purchase (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                outlet_id INTEGER,
                transaction_id TEXT,
                purchase_dt TEXT,
                supp_id INTEGER,
                product_id INTEGER,
                batch_no TEXT,
                expiry_dt TEXT,
                quantity REAL,
                purchase_price REAL,
                purchase_cgst REAL DEFAULT 0,
                purchase_sgst REAL DEFAULT 0,
                purchase_igst REAL DEFAULT 0,
                purchase_vat REAL DEFAULT 0,
                purchase_discount REAL DEFAULT 0,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                created_by TEXT,
                FOREIGN KEY(supp_id) REFERENCES md_supplier(id),
                FOREIGN KEY(product_id) REFERENCES md_products(id)
            )
        """

        private const val SQL_CREATE_TD_PURCHASE_RETURN = """
            CREATE TABLE IF NOT EXISTS td_purchase_return (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                outlet_id INTEGER,
                purchase_id INTEGER,
                return_id TEXT,
                return_dt TEXT,
                quantity REAL,
                adjust_price REAL,
                ret_cgst REAL DEFAULT 0,
                ret_sgst REAL DEFAULT 0,
                ret_igst REAL DEFAULT 0,
                ret_vat REAL DEFAULT 0,
                ret_discount REAL DEFAULT 0,
                remarks TEXT,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                created_by TEXT,
                FOREIGN KEY(purchase_id) REFERENCES td_purchase(id)
            )
        """

        private const val SQL_CREATE_TD_WRITE_OFF = """
            CREATE TABLE IF NOT EXISTS td_write_off (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                outlet_id INTEGER,
                trans_id TEXT,
                trans_dt TEXT,
                prod_id INTEGER,
                quantity REAL,
                write_off_price REAL,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                created_by TEXT,
                FOREIGN KEY(prod_id) REFERENCES md_products(id)
            )
        """

        private const val SQL_CREATE_TD_BILLS = """
            CREATE TABLE IF NOT EXISTS td_bills (
                receipt_no INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                outlet_id INTEGER,
                bill_number TEXT,
                bill_date TEXT,
                bill_date_time TEXT,
                customer_id INTEGER,
                operator_id INTEGER,
                waiter_id INTEGER,
                bill_type TEXT CHECK(bill_type IN ('CASH','CREDIT','CARD','ONLINE','VOID')),
                tot_price REAL DEFAULT 0,
                tot_discount_amount REAL DEFAULT 0,
                tot_discount_percentage REAL DEFAULT 0,
                discount_flag INTEGER NOT NULL DEFAULT 0,
                discount_type TEXT,
                tot_cgst_amount REAL DEFAULT 0,
                tot_sgst_amount REAL DEFAULT 0,
                tot_igst_amount REAL DEFAULT 0,
                tot_vat_amount REAL DEFAULT 0,
                tot_other_charges_amount REAL DEFAULT 0,
                tot_round_off_amount REAL DEFAULT 0,
                net_amount REAL DEFAULT 0,
                amount_in_words TEXT,
                gst_flag INTEGER NOT NULL DEFAULT 0,
                vat_flag INTEGER NOT NULL DEFAULT 0,
                is_mrp_billing INTEGER NOT NULL DEFAULT 0,
                is_return_bill INTEGER NOT NULL DEFAULT 0,
                is_duplicate INTEGER NOT NULL DEFAULT 0,
                is_voided INTEGER NOT NULL DEFAULT 0,
                bill_status TEXT CHECK(bill_status IN ('DRAFT','COMPLETED','CANCELLED')) DEFAULT 'DRAFT',
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(customer_id) REFERENCES md_customers(id),
                FOREIGN KEY(operator_id) REFERENCES md_users(id),
                FOREIGN KEY(waiter_id) REFERENCES md_waiters(id)
            )
        """

        private const val SQL_CREATE_TD_BILL_ITEMS = """
            CREATE TABLE IF NOT EXISTS td_bill_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                receipt_no INTEGER,
                trans_dt TEXT,
                bill_id INTEGER,
                product_id INTEGER,
                batch_id INTEGER,
                quantity REAL,
                unit_id INTEGER,
                rate REAL,
                item_subtotal REAL,
                discount_amount REAL DEFAULT 0,
                discount_percentage REAL DEFAULT 0,
                cgst_rate REAL DEFAULT 0,
                sgst_rate REAL DEFAULT 0,
                igst_rate REAL DEFAULT 0,
                vat_rate REAL DEFAULT 0,
                cgst_amount REAL DEFAULT 0,
                sgst_amount REAL DEFAULT 0,
                igst_amount REAL DEFAULT 0,
                vat_amount REAL DEFAULT 0,
                item_total REAL,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(bill_id) REFERENCES td_bills(receipt_no),
                FOREIGN KEY(product_id) REFERENCES md_products(id),
                FOREIGN KEY(batch_id) REFERENCES md_batch_stock(id),
                FOREIGN KEY(unit_id) REFERENCES md_units(id)
            )
        """

        private const val SQL_CREATE_TD_PAYMENTS = """
            CREATE TABLE IF NOT EXISTS td_payments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                receipt_no INTEGER,
                bill_id INTEGER,
                payment_mode TEXT CHECK(payment_mode IN ('CASH','UPI','CARD','CHEQUE','ONLINE','CREDIT')),
                amount_paid REAL DEFAULT 0,
                change_amount REAL DEFAULT 0,
                upi_transaction_id TEXT,
                card_last_four TEXT,
                cheque_number TEXT,
                payment_status TEXT CHECK(payment_status IN ('PENDING','PARTIAL','COMPLETED','FAILED')) DEFAULT 'PENDING',
                balance_amount REAL DEFAULT 0,
                payment_date TEXT,
                cust_name TEXT,
                cust_gstin TEXT,
                cust_phone TEXT,
                cust_id INTEGER,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(bill_id) REFERENCES td_bills(receipt_no),
                FOREIGN KEY(cust_id) REFERENCES md_customers(id)
            )
        """

        private const val SQL_CREATE_TD_SALE_RETURNS = """
            CREATE TABLE IF NOT EXISTS td_sale_returns (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                original_bill_id INTEGER,
                return_bill_number TEXT UNIQUE,
                return_date TEXT,
                return_time TEXT,
                operator_id INTEGER,
                total_return_amount REAL DEFAULT 0,
                return_status TEXT CHECK(return_status IN ('PENDING','APPROVED','COMPLETED','REJECTED')) DEFAULT 'PENDING',
                return_reason TEXT,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(original_bill_id) REFERENCES td_bills(receipt_no),
                FOREIGN KEY(operator_id) REFERENCES md_users(id)
            )
        """

        private const val SQL_CREATE_TD_RETURN_ITEMS = """
            CREATE TABLE IF NOT EXISTS td_return_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                return_id INTEGER,
                bill_item_id INTEGER,
                product_id INTEGER,
                return_quantity REAL,
                return_amount REAL,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(return_id) REFERENCES td_sale_returns(id),
                FOREIGN KEY(bill_item_id) REFERENCES td_bill_items(id),
                FOREIGN KEY(product_id) REFERENCES md_products(id)
            )
        """

        private const val SQL_CREATE_TD_STOCK_TRANSACTIONS = """
            CREATE TABLE IF NOT EXISTS td_stock_transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                product_id INTEGER,
                batch_id INTEGER,
                transaction_type TEXT CHECK(transaction_type IN ('PURCHASE','SALE','RETURN','ADJUSTMENT','DAMAGE_WRITEOFF')),
                quantity REAL,
                reference_number TEXT,
                transaction_date TEXT,
                notes TEXT,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(product_id) REFERENCES md_products(id),
                FOREIGN KEY(batch_id) REFERENCES md_batch_stock(id)
            )
        """

        private const val SQL_CREATE_TD_CUSTOMER_LEDGER = """
            CREATE TABLE IF NOT EXISTS td_customer_ledger (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_id INTEGER,
                bill_id INTEGER,
                payment_id INTEGER,
                transaction_type TEXT CHECK(transaction_type IN ('DEBIT','CREDIT')),
                amount REAL,
                balance REAL,
                transaction_date TEXT,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(customer_id) REFERENCES md_customers(id),
                FOREIGN KEY(bill_id) REFERENCES td_bills(receipt_no),
                FOREIGN KEY(payment_id) REFERENCES td_payments(id)
            )
        """

        private const val SQL_CREATE_TD_ADVANCE_PAYMENTS = """
            CREATE TABLE IF NOT EXISTS td_advance_payments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_id INTEGER,
                advance_amount REAL,
                remaining_balance REAL,
                payment_date TEXT,
                notes TEXT,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(customer_id) REFERENCES md_customers(id)
            )
        """

        private const val SQL_CREATE_TD_KOT = """
            CREATE TABLE IF NOT EXISTS td_kot (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                bill_id INTEGER,
                kot_number TEXT,
                table_number TEXT,
                waiter_id INTEGER,
                kot_date TEXT,
                kot_time TEXT,
                status TEXT CHECK(status IN ('OPEN','RECEIVED','PREPARING','READY','SERVED','CANCELLED')) DEFAULT 'OPEN',
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(bill_id) REFERENCES td_bills(receipt_no),
                FOREIGN KEY(waiter_id) REFERENCES md_waiters(id)
            )
        """

        private const val SQL_CREATE_TD_KOT_ITEMS = """
            CREATE TABLE IF NOT EXISTS td_kot_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kot_id INTEGER,
                product_id INTEGER,
                quantity REAL,
                special_instructions TEXT,
                status TEXT CHECK(status IN ('PENDING','PREPARED','DELIVERED')) DEFAULT 'PENDING',
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(kot_id) REFERENCES td_kot(id),
                FOREIGN KEY(product_id) REFERENCES md_products(id)
            )
        """

        private const val SQL_CREATE_TD_BILL_PRINTS = """
            CREATE TABLE IF NOT EXISTS td_bill_prints (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                bill_id INTEGER,
                print_type TEXT CHECK(print_type IN ('ORIGINAL','DUPLICATE','REPRINT')),
                print_date TEXT,
                printer_name TEXT,
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(bill_id) REFERENCES td_bills(receipt_no)
            )
        """
    }
}

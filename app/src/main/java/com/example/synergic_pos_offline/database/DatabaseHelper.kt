package com.example.synergic_pos_offline.database

import android.content.Context
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
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // Non-destructive migrations for existing databases (no version bump / data loss).
        addColumnIfMissing(db, Tables.MD_APP_SETTINGS, "device_id", "TEXT")
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
        // Pre-release schema: drop and recreate rather than migrate.
        for (table in ALL_TABLES.asReversed()) {
            db.execSQL("DROP TABLE IF EXISTS $table")
        }
        onCreate(db)
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

        private const val SQL_CREATE_MD_PRODUCTS = """
            CREATE TABLE IF NOT EXISTS md_products (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                product_name TEXT,
                hsn_code TEXT,
                stock_alert_qty REAL DEFAULT 0,
                bar_code TEXT,
                category_id INTEGER,
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
                bill_type TEXT CHECK(bill_type IN ('CASH','CREDIT','ONLINE','VOID')),
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
                payment_mode TEXT CHECK(payment_mode IN ('CASH','UPI','CARD','CHEQUE','ONLINE')),
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

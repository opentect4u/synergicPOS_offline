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
        if (db.isReadOnly) return
        db.setForeignKeyConstraintsEnabled(true)
        // Non-destructive migrations for existing databases (no version bump / data loss).
        addColumnIfMissing(db, Tables.MD_APP_SETTINGS, "device_id", "TEXT")
        addColumnIfMissing(db, Tables.MD_PRODUCTS, "sku", "TEXT")
        addColumnIfMissing(db, Tables.MD_PRODUCTS, "brand", "TEXT")
        // Rate-name master + the rate's link to it (non-destructive).
        runCatching { db.execSQL(SQL_CREATE_MD_RATE_NAME) }
        // The shift master, and the user's place on it. Added here rather than
        // through a version bump for the same reason the rate-name master was: it
        // takes nothing away, so an existing till gains it on the next open without
        // a migration that could fail half-done.
        runCatching { db.execSQL(SQL_CREATE_MD_SHIFTS) }
        // The extra-charges master. Created here rather than behind a version bump,
        // for the same reason the two above are: it takes nothing away, so a till
        // that already exists gains it on its next open.
        runCatching { db.execSQL(SQL_CREATE_MD_CHARGES) }
        addColumnIfMissing(db, Tables.MD_USERS, "shift_id", "INTEGER")
        // Which sections this user may open. Access used to be one set of flags for
        // the whole till, which meant every general user saw the same thing; it is
        // granted per user now, from the Add/Edit User form.
        //
        // Default 0: a user created before this had no access of their own, and an
        // account that silently gained the Master section on upgrade would be worse
        // than one an admin has to grant it to.
        addColumnIfMissing(db, Tables.MD_USERS, "access_master", "INTEGER DEFAULT 0")
        addColumnIfMissing(db, Tables.MD_USERS, "access_settings", "INTEGER DEFAULT 0")
        addColumnIfMissing(db, Tables.MD_USERS, "access_reports", "INTEGER DEFAULT 0")
        addColumnIfMissing(db, Tables.MD_USERS, "access_about_app", "INTEGER DEFAULT 0")
        // Older installs created md_rate_name before it was store-scoped and audited;
        // add the missing columns and attach any pre-existing rows to the registered
        // store, so the list, add/edit/delete and store filtering all work on them.
        addColumnIfMissing(db, Tables.MD_RATE_NAME, "store_id", "INTEGER")
        addColumnIfMissing(db, Tables.MD_RATE_NAME, "is_active", "INTEGER NOT NULL DEFAULT 1")
        addColumnIfMissing(db, Tables.MD_RATE_NAME, "created_at", "TEXT")
        addColumnIfMissing(db, Tables.MD_RATE_NAME, "modified_at", "TEXT")
        addColumnIfMissing(db, Tables.MD_RATE_NAME, "created_by", "TEXT")
        addColumnIfMissing(db, Tables.MD_RATE_NAME, "modified_by", "TEXT")
        runCatching {
            db.execSQL(
                "UPDATE ${Tables.MD_RATE_NAME} SET store_id = " +
                    "(SELECT store_id FROM ${Tables.MD_REGISTRATION} ORDER BY store_id ASC LIMIT 1) " +
                    "WHERE store_id IS NULL"
            )
        }
        addColumnIfMissing(db, Tables.MD_PRODUCT_RATES, "rate_name_id", "INTEGER")
        // sell_price / sale_price are duplicate columns for the selling price. Reads
        // use COALESCE(sell_price, sale_price) and writes set both; backfill legacy
        // rows so whichever one was populated fills the other.
        runCatching {
            db.execSQL("UPDATE ${Tables.MD_PRODUCT_RATES} SET sell_price = sale_price WHERE sell_price IS NULL AND sale_price IS NOT NULL")
            db.execSQL("UPDATE ${Tables.MD_PRODUCT_RATES} SET sale_price = sell_price WHERE sale_price IS NULL AND sell_price IS NOT NULL")
        }
        // Restaurant-mode product attributes (Veg/Non-Veg/Egg, spice, prep time, availability).
        addColumnIfMissing(db, Tables.MD_PRODUCTS, "food_type", "TEXT")
        addColumnIfMissing(db, Tables.MD_PRODUCTS, "spice_level", "TEXT")
        addColumnIfMissing(db, Tables.MD_PRODUCTS, "prep_time", "TEXT")
        addColumnIfMissing(db, Tables.MD_PRODUCTS, "availability", "TEXT")
        // A batch has always carried an expiry; the date it was made is captured
        // alongside it when a product is added with stock tracking on.
        addColumnIfMissing(db, Tables.MD_BATCH_STOCK, "mfg_date", "TEXT")
        // Which way the stock moved - 'IN' or 'OUT'. transaction_type says what kind
        // of movement it was (a RETURN is stock coming back out to a supplier here),
        // which is not the same question as whether the count went up or down.
        // Added without the CHECK the fresh schema carries: SQLite cannot attach a
        // constraint to an existing column, and the writers are the two screens below.
        addColumnIfMissing(db, Tables.TD_STOCK_TRANSACTIONS, "stock_flow", "TEXT")
        addColumnIfMissing(db, Tables.TD_BILLS, "bill_seq_no", "INTEGER")
        addColumnIfMissing(db, Tables.TD_BILLS, "settings_snapshot", "TEXT")
        // Restaurant-mode bill fields: which table/section it was, dine-in vs take-away,
        // and the service charge kept separate from generic other-charges.
        addColumnIfMissing(db, Tables.TD_BILLS, "table_number", "TEXT")
        // Which section that table was in. Null on every grocery bill, and on the
        // restaurant's take-away ones; a table number repeats in every section, so
        // without it the history cannot say which of them was served.
        addColumnIfMissing(db, Tables.TD_BILLS, "table_section", "TEXT")
        addColumnIfMissing(db, Tables.TD_BILLS, "order_type", "TEXT")
        addColumnIfMissing(db, Tables.TD_BILLS, "service_charge_amount", "REAL DEFAULT 0")
        // The delete archive mirrors the live bill, column for column.
        addColumnIfMissing(db, Tables.TD_BILLS_DELETE, "table_section", "TEXT")
        // Store-scope bill lines and payments directly (not only via their bill), then
        // backfill existing rows with the store of the bill they belong to.
        if (addColumnIfMissing(db, Tables.TD_BILL_ITEMS, "store_id", "INTEGER")) {
            runCatching {
                db.execSQL(
                    "UPDATE ${Tables.TD_BILL_ITEMS} SET store_id = " +
                        "(SELECT store_id FROM ${Tables.TD_BILLS} WHERE ${Tables.TD_BILLS}.receipt_no = ${Tables.TD_BILL_ITEMS}.bill_id) " +
                        "WHERE store_id IS NULL"
                )
            }
        }
        if (addColumnIfMissing(db, Tables.TD_PAYMENTS, "store_id", "INTEGER")) {
            runCatching {
                db.execSQL(
                    "UPDATE ${Tables.TD_PAYMENTS} SET store_id = " +
                        "(SELECT store_id FROM ${Tables.TD_BILLS} WHERE ${Tables.TD_BILLS}.receipt_no = ${Tables.TD_PAYMENTS}.bill_id) " +
                        "WHERE store_id IS NULL"
                )
            }
        }
        // Bills created before bill_seq_no existed carried a plain receipt_no-based
        // number (see BillDao's old formatBillNumber), so that is what continuing
        // the sequence from here has to match up with.
        runCatching {
            db.execSQL("UPDATE ${Tables.TD_BILLS} SET bill_seq_no = receipt_no WHERE bill_seq_no IS NULL")
        }
        // Birthday / anniversary use the existing dob / dom columns; ensure they
        // exist on databases created before those columns were added.
        db.execSQL(SQL_CREATE_MD_CAPTIONS)
        addColumnIfMissing(db, Tables.MD_CUSTOMERS, "dob", "TEXT")
        addColumnIfMissing(db, Tables.MD_CUSTOMERS, "dom", "TEXT")
        // New restaurant sections + tables (non-destructive; created if absent).
        runCatching { db.execSQL(SQL_CREATE_MD_SECTION) }
        runCatching { db.execSQL(SQL_CREATE_MD_TABLE) }
        runCatching { db.execSQL(SQL_CREATE_MD_TABLE_UNIT) }
        runCatching { db.execSQL(SQL_CREATE_MD_SUBTABLE) }
        runCatching { db.execSQL(SQL_CREATE_TD_ASSIGN_WAITER) }
        runCatching { db.execSQL(SQL_CREATE_TD_RUNNING_ORDER) }
        runCatching { db.execSQL(SQL_CREATE_TD_RUNNING_ORDER_ITEMS) }
        addColumnIfMissing(db, Tables.TD_RUNNING_ORDER, "order_note", "TEXT")
        addColumnIfMissing(db, Tables.TD_RUNNING_ORDER, "merged_tables", "TEXT")
        addColumnIfMissing(db, Tables.TD_RUNNING_ORDER_ITEMS, "kot_qty", "REAL DEFAULT 0")
        // Per-item GST captured at order time, so the bill taxes each product dynamically.
        addColumnIfMissing(db, Tables.TD_RUNNING_ORDER_ITEMS, "cgst_rate", "REAL DEFAULT 0")
        addColumnIfMissing(db, Tables.TD_RUNNING_ORDER_ITEMS, "sgst_rate", "REAL DEFAULT 0")
        // A running order carried CGST and SGST but no VAT, so a VAT-rated dish
        // lost its rate the moment it went on a table - and the bill printed from
        // that order could not charge a tax it no longer knew about.
        addColumnIfMissing(db, Tables.TD_RUNNING_ORDER_ITEMS, "vat_rate", "REAL DEFAULT 0")
        // KOT lifecycle: link a KOT to its running order, and allow the CLOSED /
        // COMPLETE statuses the restaurant flow sets (see [ensureKotStatusSchema]).
        ensureKotStatusSchema(db)
        ensureTableStatusSchema(db)
        // Items already sent under the old flag must not be re-sent: mark their full
        // quantity as already gone to the kitchen.
        runCatching {
            db.execSQL(
                "UPDATE ${Tables.TD_RUNNING_ORDER_ITEMS} SET kot_qty = quantity " +
                    "WHERE kot_printed = 1 AND kot_qty = 0"
            )
        }
        addColumnIfMissing(db, Tables.MD_TABLE, "no_of_tables", "INTEGER")
        addColumnIfMissing(db, Tables.MD_TABLE, "from_table_no", "INTEGER")
        addColumnIfMissing(db, Tables.MD_TABLE, "to_table_no", "INTEGER")
        addColumnIfMissing(db, Tables.MD_TABLE, "waiter_id", "INTEGER")
        // A due collection is numbered and takes a tender mode, so its receipt can
        // be traced back to the row that produced it. The table itself is ensured
        // first: ALTER TABLE on one that was never created would fail the open.
        db.execSQL(SQL_CREATE_TD_ADVANCE_PAYMENTS)
        addColumnIfMissing(db, Tables.TD_ADVANCE_PAYMENTS, "receipt_number", "TEXT")
        addColumnIfMissing(db, Tables.TD_ADVANCE_PAYMENTS, "payment_mode", "TEXT")
        // Sale returns and credit recoveries share one continuous bill-number sequence
        // with normal sales — bill_seq_no is the shared counter (see BillDao).
        addColumnIfMissing(db, Tables.TD_ADVANCE_PAYMENTS, "bill_seq_no", "INTEGER")
        addColumnIfMissing(db, Tables.TD_SALE_RETURNS, "bill_seq_no", "INTEGER")
        // Audit trail for header / footer / caption masters.
        listOf(Tables.MD_HEADERS, Tables.MD_FOOTERS, Tables.MD_CAPTIONS).forEach { t ->
            addColumnIfMissing(db, t, "created_at", "TEXT")
            addColumnIfMissing(db, t, "modified_at", "TEXT")
            addColumnIfMissing(db, t, "created_by", "TEXT")
            addColumnIfMissing(db, t, "modified_by", "TEXT")
        }
        ensureProductsSchema(db)
        recreateProductRatesIfOldSchema(db)
        // "default" is a reserved word, so it must be quoted in the ALTER.
        if (!columnExists(db, Tables.MD_PRODUCT_RATES, "default")) {
            runCatching {
                db.execSQL("ALTER TABLE ${Tables.MD_PRODUCT_RATES} ADD COLUMN \"default\" INTEGER DEFAULT 0")
            }
        }
        // Every product rate's sku mirrors its own id.
        runCatching {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS trg_product_rates_sku_from_id
                AFTER INSERT ON ${Tables.MD_PRODUCT_RATES}
                FOR EACH ROW
                BEGIN
                    UPDATE ${Tables.MD_PRODUCT_RATES} SET sku = NEW.id WHERE id = NEW.id;
                END
                """.trimIndent()
            )
        }
        // And every product's sku mirrors its own id, the same way.
        //
        // Done in the database rather than at each insert because there are several
        // ways a product gets created - the Add Product popup, a bulk upload, the
        // seeder - and a rule that lives in one of them is a rule the other two get
        // wrong. It is the id that is copied, so a product's sku is unique without
        // anything having to allocate it.
        runCatching {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS trg_products_sku_from_id
                AFTER INSERT ON ${Tables.MD_PRODUCTS}
                FOR EACH ROW
                BEGIN
                    UPDATE ${Tables.MD_PRODUCTS} SET sku = NEW.id WHERE id = NEW.id;
                END
                """.trimIndent()
            )
        }
        // Products created before that trigger existed have no sku at all; give them
        // the same one they would have been given. Only the blanks are touched, so a
        // product whose sku was set by hand keeps it.
        runCatching {
            db.execSQL(
                "UPDATE ${Tables.MD_PRODUCTS} SET sku = id WHERE sku IS NULL OR trim(sku) = ''"
            )
        }
        // store_id on products and their rates is sourced from md_registration (the
        // verified store first) when the insert didn't supply one.
        runCatching { db.execSQL(storeIdTrigger("trg_products_store_id", Tables.MD_PRODUCTS)) }
        runCatching { db.execSQL(storeIdTrigger("trg_product_rates_store_id", Tables.MD_PRODUCT_RATES)) }
        // IGST setting was removed; drop any leftover row.
        runCatching {
            db.execSQL("DELETE FROM ${Tables.MD_APP_SETTINGS} WHERE setting_name = 'IGST'")
        }
        // Last, and after every table it touches is known to exist: fold a device
        // that is holding two stores back onto one, so the settings the DAOs read
        // are the settings that were saved.
        runCatching { repairDuplicateStores(db) }
    }

    /** Builds an AFTER INSERT trigger that fills a null store_id from md_registration. */
    private fun storeIdTrigger(name: String, table: String): String = """
        CREATE TRIGGER IF NOT EXISTS $name
        AFTER INSERT ON $table
        FOR EACH ROW WHEN NEW.store_id IS NULL
        BEGIN
            UPDATE $table SET store_id = COALESCE(
                (SELECT store_id FROM ${Tables.MD_REGISTRATION} WHERE verify_flag = 1 ORDER BY store_id LIMIT 1),
                (SELECT store_id FROM ${Tables.MD_REGISTRATION} ORDER BY store_id LIMIT 1)
            ) WHERE id = NEW.id;
        END
    """.trimIndent()

    /**
     * Drops and recreates md_product_rates with the new single-rate schema when an
     * old (rate_1/rate_2/rate_3) table is found. Detected by the new `rate_name`
     * column being absent. This is destructive for md_product_rates - product rates
     * must be re-entered - which is intended for the schema change.
     */
    private fun recreateProductRatesIfOldSchema(db: SQLiteDatabase) {
        if (columnExists(db, Tables.MD_PRODUCT_RATES, "rate_name")) return
        runCatching {
            db.setForeignKeyConstraintsEnabled(false)
            db.beginTransaction()
            try {
                db.execSQL("DROP TABLE IF EXISTS ${Tables.MD_PRODUCT_RATES}")
                db.execSQL(SQL_CREATE_MD_PRODUCT_RATES)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            createIndexes(db)
            db.setForeignKeyConstraintsEnabled(true)
        }.onFailure { android.util.Log.e("DBMigrate", "Failed to recreate md_product_rates", it) }
    }

    /**
     * Brings td_kot / td_kot_items up to the restaurant KOT lifecycle: a
     * `running_order_id` link and the `CLOSED` (kot) / `COMPLETE` (item) statuses.
     * Both carry a CHECK on `status`, which SQLite can only widen by rebuilding the
     * table, so this re-creates them (data preserved) when the stored schema is old.
     * Foreign keys are toggled off around the rebuild, as SQLite requires.
     */
    private fun ensureKotStatusSchema(db: SQLiteDatabase) {
        runCatching { db.execSQL(SQL_CREATE_TD_KOT) }
        runCatching { db.execSQL(SQL_CREATE_TD_KOT_ITEMS) }
        val kotSql = tableSql(db, Tables.TD_KOT)
        val itemSql = tableSql(db, Tables.TD_KOT_ITEMS)
        val kotOld = kotSql != null && !(kotSql.contains("CLOSED") && kotSql.contains("running_order_id"))
        val itemOld = itemSql != null && !(itemSql.contains("COMPLETE") && itemSql.contains("CANCELLED"))
        if (!kotOld && !itemOld) return
        runCatching {
            db.setForeignKeyConstraintsEnabled(false)
            db.beginTransaction()
            try {
                if (kotOld) {
                    db.execSQL("ALTER TABLE ${Tables.TD_KOT} RENAME TO td_kot_old")
                    db.execSQL(SQL_CREATE_TD_KOT)
                    db.execSQL(
                        """
                        INSERT INTO ${Tables.TD_KOT}
                            (id, bill_id, kot_number, table_number, waiter_id, kot_date, kot_time,
                             status, created_at, modified_at, created_by, modified_by)
                        SELECT id, bill_id, kot_number, table_number, waiter_id, kot_date, kot_time,
                               status, created_at, modified_at, created_by, modified_by
                        FROM td_kot_old
                        """.trimIndent()
                    )
                    db.execSQL("DROP TABLE td_kot_old")
                }
                if (itemOld) {
                    db.execSQL("ALTER TABLE ${Tables.TD_KOT_ITEMS} RENAME TO td_kot_items_old")
                    db.execSQL(SQL_CREATE_TD_KOT_ITEMS)
                    db.execSQL(
                        """
                        INSERT INTO ${Tables.TD_KOT_ITEMS}
                            (id, kot_id, product_id, quantity, special_instructions, status,
                             created_at, modified_at, created_by, modified_by)
                        SELECT id, kot_id, product_id, quantity, special_instructions, status,
                               created_at, modified_at, created_by, modified_by
                        FROM td_kot_items_old
                        """.trimIndent()
                    )
                    db.execSQL("DROP TABLE td_kot_items_old")
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.setForeignKeyConstraintsEnabled(true)
        }.onFailure { android.util.Log.e("DBMigrate", "Failed to rebuild KOT tables", it) }
    }

    /**
     * Brings md_table, md_table_unit and md_subtable up to the restaurant status
     * lifecycle by adding 'KOT Printed' to their table_status CHECK. Since SQLite
     * cannot widen a CHECK without rebuilding, this re-creates the tables (preserving
     * data) if they are found to be missing the new status.
     */
    private fun ensureTableStatusSchema(db: SQLiteDatabase) {
        val tableSql = tableSql(db, Tables.MD_TABLE)
        val unitSql = tableSql(db, Tables.MD_TABLE_UNIT)
        val subSql = tableSql(db, Tables.MD_SUBTABLE)

        val tableOld = tableSql != null && !tableSql.contains("KOT Printed")
        val unitOld = unitSql != null && !unitSql.contains("KOT Printed")
        val subOld = subSql != null && !subSql.contains("KOT Printed")

        if (!tableOld && !unitOld && !subOld) return

        runCatching {
            db.setForeignKeyConstraintsEnabled(false)
            db.beginTransaction()
            try {
                if (tableOld) {
                    db.execSQL("ALTER TABLE ${Tables.MD_TABLE} RENAME TO md_table_old")
                    db.execSQL(SQL_CREATE_MD_TABLE)
                    db.execSQL(
                        """
                        INSERT INTO ${Tables.MD_TABLE} (id, store_id, outlet_id, section_id, no_of_tables, from_table_no, to_table_no, table_code, floor_no, seating_capacity, table_status, waiter_id, remarks, created_at, modified_at, created_by, modified_by)
                        SELECT id, store_id, outlet_id, section_id, no_of_tables, from_table_no, to_table_no, table_code, floor_no, seating_capacity, table_status, waiter_id, remarks, created_at, modified_at, created_by, modified_by
                        FROM md_table_old
                        """.trimIndent()
                    )
                    db.execSQL("DROP TABLE md_table_old")
                }
                if (unitOld) {
                    db.execSQL("ALTER TABLE ${Tables.MD_TABLE_UNIT} RENAME TO md_table_unit_old")
                    db.execSQL(SQL_CREATE_MD_TABLE_UNIT)
                    db.execSQL(
                        """
                        INSERT INTO ${Tables.MD_TABLE_UNIT} (id, store_id, table_id, section_id, table_code, floor_no, seating_capacity, table_status, created_at, modified_at, created_by, modified_by)
                        SELECT id, store_id, table_id, section_id, table_code, floor_no, seating_capacity, table_status, created_at, modified_at, created_by, modified_by
                        FROM md_table_unit_old
                        """.trimIndent()
                    )
                    db.execSQL("DROP TABLE md_table_unit_old")
                }
                if (subOld) {
                    db.execSQL("ALTER TABLE ${Tables.MD_SUBTABLE} RENAME TO md_subtable_old")
                    db.execSQL(SQL_CREATE_MD_SUBTABLE)
                    db.execSQL(
                        """
                        INSERT INTO ${Tables.MD_SUBTABLE} (id, store_id, table_id, parent_code, sub_code, suffix, table_status, created_at, modified_at, created_by, modified_by)
                        SELECT id, store_id, table_id, parent_code, sub_code, suffix, table_status, created_at, modified_at, created_by, modified_by
                        FROM md_subtable_old
                        """.trimIndent()
                    )
                    db.execSQL("DROP TABLE md_subtable_old")
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.setForeignKeyConstraintsEnabled(true)
        }.onFailure { android.util.Log.e("DBMigrate", "Failed to rebuild table status tables", it) }
    }

    /** The stored CREATE statement for [table], or null if it doesn't exist. */
    private fun tableSql(db: SQLiteDatabase, table: String): String? =
        db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** True if [table] has a column named [column]. */
    private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            generateSequence { if (c.moveToNext()) c.getString(nameIdx) else null }.any { it == column }
        }

    /** True if [table] has a foreign key on [fromColumn] referencing [refTable]. */
    private fun foreignKeyExists(db: SQLiteDatabase, table: String, fromColumn: String, refTable: String): Boolean =
        db.rawQuery("PRAGMA foreign_key_list($table)", null).use { c ->
            val tableIdx = c.getColumnIndex("table")
            val fromIdx = c.getColumnIndex("from")
            generateSequence { if (c.moveToNext()) c.getString(tableIdx) to c.getString(fromIdx) else null }
                .any { it.first == refTable && it.second == fromColumn }
        }

    /**
     * Rebuilds md_products to the current schema when it is out of date - either the
     * old `gst_rate` column is still present, or the `store_id` foreign key is missing.
     * Rebuilding is the portable way to drop a column / add a constraint (SQLite has no
     * in-place support that older Android builds carry). Data is preserved; foreign
     * keys are toggled off around the rebuild as SQLite requires.
     */
    private fun ensureProductsSchema(db: SQLiteDatabase) {
        val needsRebuild = columnExists(db, Tables.MD_PRODUCTS, "gst_rate") ||
            !foreignKeyExists(db, Tables.MD_PRODUCTS, "store_id", Tables.MD_REGISTRATION)
        if (!needsRebuild) return
        runCatching {
            db.setForeignKeyConstraintsEnabled(false)
            db.beginTransaction()
            try {
                db.execSQL("ALTER TABLE ${Tables.MD_PRODUCTS} RENAME TO md_products_old")
                db.execSQL(SQL_CREATE_MD_PRODUCTS)
                db.execSQL(
                    """
                    INSERT INTO ${Tables.MD_PRODUCTS}
                        (id, store_id, product_name, sku, brand, hsn_code, stock_alert_qty,
                         bar_code, category_id, product_image, created_at, modified_at,
                         created_by, modified_by)
                    SELECT id, store_id, product_name, sku, brand, hsn_code, stock_alert_qty,
                           bar_code, category_id, product_image, created_at, modified_at,
                           created_by, modified_by
                    FROM md_products_old
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE md_products_old")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            createIndexes(db)
            db.setForeignKeyConstraintsEnabled(true)
        }.onFailure { android.util.Log.e("DBMigrate", "Failed to rebuild md_products", it) }
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

    // ---- One device, one store ---------------------------------------------
    //
    // A till serves a single shop, and md_registration is meant to hold a single row
    // for it. Everything store-scoped depends on that: settings, masters and bills
    // are all read back with "the registered store's id", which every DAO resolves
    // the same way - the first row of md_registration.
    //
    // A second row breaks all of it at once. The server issues the real store_id at
    // verification, which is rarely the placeholder the local registration created,
    // so the device ends up holding both. The DAOs then read under one id while the
    // data is written and re-pointed under the other, and every setting comes back
    // as its default - a saved Restaurant mode reads as Grocery, the mode's own
    // default. Nothing is lost; it is simply being looked for under the wrong store.
    //
    // So the rule is enforced rather than assumed: the verified store absorbs any
    // other, and the rows that named the other are carried over to it.

    /** The store ids md_registration currently holds, lowest first. */
    fun registrationStoreIds(db: SQLiteDatabase): List<Long> {
        val ids = mutableListOf<Long>()
        runCatching {
            db.query(
                Tables.MD_REGISTRATION, arrayOf("store_id"),
                null, null, null, null, "store_id ASC"
            ).use { c -> while (c.moveToNext()) if (!c.isNull(0)) ids.add(c.getLong(0)) }
        }
        return ids
    }

    /**
     * Makes [keep] the only store on the device: every store-scoped row is carried
     * over to it and the other registration rows are dropped.
     *
     * Children are re-pointed before their parent goes, so nothing is orphaned and
     * the foreign key on md_users.store_id holds throughout. Runs in the caller's
     * transaction - [saveVerifiedStore] does this as part of recording the store.
     */
    fun consolidateStores(db: SQLiteDatabase, keep: Long) {
        val others = registrationStoreIds(db).filter { it != keep }
        if (others.isEmpty()) return
        val orphaned = others.joinToString(",")
        for (t in tablesWithStoreId(db)) {
            runCatching {
                db.execSQL(
                    "UPDATE $t SET store_id = ? WHERE store_id IS NULL OR store_id IN ($orphaned)",
                    arrayOf<Any>(keep)
                )
            }
        }
        runCatching {
            db.execSQL("DELETE FROM ${Tables.MD_REGISTRATION} WHERE store_id IN ($orphaned)")
        }
        dedupeAppSettings(db)
    }

    /**
     * Collapses a device that already holds two stores, on the next open.
     *
     * The verified row is the one to keep: it carries the store_id the server issued
     * and the one the signed-in user belongs to. With no verified row, the newest is
     * kept - a placeholder that was superseded is the older of the two.
     */
    private fun repairDuplicateStores(db: SQLiteDatabase) {
        val ids = registrationStoreIds(db)
        if (ids.size < 2) return
        val keep = verifiedStoreId(db) ?: ids.max()
        db.beginTransaction()
        try {
            consolidateStores(db, keep)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun verifiedStoreId(db: SQLiteDatabase): Long? {
        runCatching {
            db.query(
                Tables.MD_REGISTRATION, arrayOf("store_id"),
                "verify_flag = 1", null, null, null, "store_id DESC", "1"
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) }
        }
        return null
    }

    /**
     * Every table carrying a store_id, md_ and td_ alike, except md_registration -
     * there store_id is the primary key that identifies the store rather than a
     * reference to it, so it is moved by [consolidateStores] and never re-stamped.
     */
    fun tablesWithStoreId(db: SQLiteDatabase): List<String> {
        val tables = mutableListOf<String>()
        runCatching {
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null
            ).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    if (name == Tables.MD_REGISTRATION) continue
                    if (columnExists(db, name, "store_id")) tables.add(name)
                }
            }
        }
        return tables
    }

    /**
     * Drops the copies a split store left behind.
     *
     * While the two ids were live, a save read nothing under the id it looked under
     * and so inserted where it meant to update - once per save, per setting. The
     * newest row is the one that was last written, and is the one kept.
     */
    private fun dedupeAppSettings(db: SQLiteDatabase) {
        runCatching {
            db.execSQL(
                "DELETE FROM ${Tables.MD_APP_SETTINGS} WHERE id NOT IN " +
                    "(SELECT MAX(id) FROM ${Tables.MD_APP_SETTINGS} GROUP BY setting_name)"
            )
        }
    }

    /**
     * Adds [column] to [table] if it isn't already present, leaving data intact.
     * Returns true when the column was actually added (so callers can backfill it).
     */
    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, type: String): Boolean {
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            generateSequence { if (c.moveToNext()) c.getString(nameIdx) else null }.any { it == column }
        }
        if (!exists) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
        }
        return !exists
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Master tables
        db.execSQL(SQL_CREATE_MD_REGISTRATION)
        db.execSQL(SQL_CREATE_MD_USERS)
        db.execSQL(SQL_CREATE_MD_CATEGORY)
        db.execSQL(SQL_CREATE_MD_UNITS)
        db.execSQL(SQL_CREATE_MD_SHIFTS)
        db.execSQL(SQL_CREATE_MD_RATE_NAME)
        db.execSQL(SQL_CREATE_MD_PRODUCTS)
        db.execSQL(SQL_CREATE_MD_PRODUCT_RATES)
        db.execSQL(SQL_CREATE_MD_CUSTOMERS)
        db.execSQL(SQL_CREATE_MD_DESCRIPTION)
        db.execSQL(SQL_CREATE_MD_WAITERS)
        db.execSQL(SQL_CREATE_MD_HEADERS)
        db.execSQL(SQL_CREATE_MD_FOOTERS)
        db.execSQL(SQL_CREATE_MD_CAPTIONS)
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
        db.execSQL(SQL_CREATE_MD_SECTION)
        db.execSQL(SQL_CREATE_MD_TABLE)
        db.execSQL(SQL_CREATE_MD_TABLE_UNIT)
        db.execSQL(SQL_CREATE_MD_SUBTABLE)
        db.execSQL(SQL_CREATE_TD_ASSIGN_WAITER)
        db.execSQL(SQL_CREATE_TD_RUNNING_ORDER)
        db.execSQL(SQL_CREATE_TD_RUNNING_ORDER_ITEMS)

        // Transaction tables
        db.execSQL(SQL_CREATE_TD_PURCHASE)
        db.execSQL(SQL_CREATE_TD_PURCHASE_RETURN)
        db.execSQL(SQL_CREATE_TD_WRITE_OFF)
        db.execSQL(SQL_CREATE_TD_BILLS)
        db.execSQL(SQL_CREATE_TD_BILL_ITEMS)
        db.execSQL(SQL_CREATE_TD_BILLS_DELETE)
        db.execSQL(SQL_CREATE_TD_BILL_ITEMS_DELETE)
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
        // cumulative migrations to preserve data.
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
        if (oldVersion < 14) {
            // Ensure md_printer exists before adding column in v7 if we jumped directly from < 6 to 14
            db.execSQL(SQL_CREATE_MD_PRINTER)
            addColumnIfMissing(db, Tables.MD_APP_SETTINGS, "device_id", "TEXT")
        }
        if (oldVersion < 17) {
            db.execSQL(SQL_CREATE_TD_BILLS_DELETE)
            db.execSQL(SQL_CREATE_TD_BILL_ITEMS_DELETE)
        }
        // gst_rate is dropped in onOpen via a portable table rebuild (see
        // dropProductGstRateIfPresent), which works on every SQLite version.
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

    /**
     * Empties [Tables.MD_APP_SETTINGS] - every setting on the till, for every store.
     *
     * Only ever called on the way to writing the defaults back
     * ([com.example.synergic_pos_offline.utils.DefaultSettings.restore]), which is
     * why it takes no store id: a key written before the till was registered carries
     * a null store and would survive a scoped delete, then sit alongside the row the
     * restore inserts against the real store and be read back at random.
     */
    fun clearAppSettings() {
        writableDatabase.delete(Tables.MD_APP_SETTINGS, null, null)
    }

    /**
     * Puts the printers back to how a newly installed app has them.
     *
     * Both tables, because they are one setup: [Tables.MD_OPERATING_PRINTER] holds
     * the shop's named printers and each points at a connection row in
     * [Tables.MD_PRINTER], so rebuilding the connections while leaving the named
     * printers behind would leave every one of them pointing at a row that no longer
     * exists. They go together and are set up again together.
     *
     * The rebuilt state is exactly what [onCreate] lays down: WIFI for BILL, LAN for
     * KOT and OTHERS, each with no address and no paper width, plus the unselected
     * Bluetooth and USB alternatives.
     */
    fun resetPrintersToDefaults() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(Tables.MD_OPERATING_PRINTER, null, null)
            db.delete(Tables.MD_PRINTER, null, null)
            seedDefaultPrinters(db)
            db.execSQL("UPDATE ${Tables.MD_PRINTER} SET is_selected = 1")
            addExtraPrinterTypes(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
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

    /**
     * Wipes the mode-specific business data when the app switches Grocery ↔ Restaurant:
     * the product/category masters, the units and rate names those products used,
     * restaurant masters (section/table/waiter), and all sales transactions (bills,
     * KOTs, payments, returns, running orders). Registration, users, and settings are
     * left intact. Children are cleared before parents.
     */
    fun eraseBusinessDataForModeChange() {
        val db = writableDatabase
        val order = listOf(
            Tables.TD_PAYMENTS, Tables.TD_RETURN_ITEMS, Tables.TD_SALE_RETURNS,
            Tables.TD_BILL_PRINTS, Tables.TD_BILL_ITEMS, Tables.TD_BILLS,
            Tables.TD_BILL_ITEMS_DELETE, Tables.TD_BILLS_DELETE,
            Tables.TD_KOT_ITEMS, Tables.TD_KOT,
            Tables.TD_RUNNING_ORDER_ITEMS, Tables.TD_RUNNING_ORDER,
            Tables.TD_ASSIGN_WAITER,
            Tables.MD_SUBTABLE, Tables.MD_TABLE_UNIT, Tables.MD_TABLE, Tables.MD_SECTION,
            Tables.MD_WAITERS,
            // Product rates reference units and rate names, so those go after it.
            Tables.MD_PRODUCT_RATES, Tables.MD_PRODUCTS, Tables.MD_CATEGORY,
            Tables.MD_UNITS, Tables.MD_RATE_NAME
        )
        db.beginTransaction()
        try {
            order.forEach { runCatching { db.delete(it, null, null) } }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Table names, in FK-safe creation order. */
    object Tables {
        const val MD_REGISTRATION = "md_registration"
        const val MD_USERS = "md_users"
        const val MD_CATEGORY = "md_category"
        const val MD_UNITS = "md_units"
        const val MD_SHIFTS = "md_shifts"
        const val MD_RATE_NAME = "md_rate_name"
        const val MD_CHARGES = "md_charges"
        const val MD_PRODUCTS = "md_products"
        const val MD_PRODUCT_RATES = "md_product_rates"
        const val MD_CUSTOMERS = "md_customers"
        const val MD_DESCRIPTION = "md_description"
        const val MD_WAITERS = "md_waiters"
        const val MD_HEADERS = "md_headers"
        const val MD_FOOTERS = "md_footers"
        const val MD_CAPTIONS = "md_captions"
        const val MD_LOGOS = "md_logos"
        const val MD_QR = "md_qr"
        const val MD_APP_SETTINGS = "md_app_settings"
        const val MD_SUPPLIER = "md_supplier"
        const val MD_BATCH_STOCK = "md_batch_stock"
        const val MD_VERSION = "md_version"
        const val MD_PRINTER = "md_printer"
        const val MD_OPERATING_PRINTER = "md_operating_printer"
        const val MD_SECTION = "md_section"
        const val MD_TABLE = "md_table"
        const val MD_TABLE_UNIT = "md_table_unit"
        const val MD_SUBTABLE = "md_subtable"

        const val TD_PURCHASE = "td_purchase"
        const val TD_PURCHASE_RETURN = "td_purchase_return"
        const val TD_WRITE_OFF = "td_write_off"
        const val TD_BILLS = "td_bills"
        const val TD_BILL_ITEMS = "td_bill_items"

        /**
         * Where a deleted bill and its lines go.
         *
         * Deleting a bill moves it out of [TD_BILLS] rather than erasing it, so it
         * leaves every sales report at once - each of them reads td_bills, and none
         * of them has to learn a new exclusion - while the record of it survives for
         * Bill History and the Void Bill Report to show.
         */
        const val TD_BILLS_DELETE = "td_bills_delete"
        const val TD_BILL_ITEMS_DELETE = "td_bill_items_delete"
        const val TD_PAYMENTS = "td_payments"
        const val TD_SALE_RETURNS = "td_sale_returns"
        const val TD_RETURN_ITEMS = "td_return_items"
        const val TD_STOCK_TRANSACTIONS = "td_stock_transactions"
        const val TD_CUSTOMER_LEDGER = "td_customer_ledger"
        const val TD_ADVANCE_PAYMENTS = "td_advance_payments"
        const val TD_KOT = "td_kot"
        const val TD_KOT_ITEMS = "td_kot_items"
        const val TD_BILL_PRINTS = "td_bill_prints"
        const val TD_ASSIGN_WAITER = "td_assign_waiter"
        // Live restaurant billing: running (open) table orders + their items.
        const val TD_RUNNING_ORDER = "td_running_order"
        const val TD_RUNNING_ORDER_ITEMS = "td_running_order_items"
    }

    companion object {
        private const val DATABASE_NAME = "synergic_pos.db"
        private const val DATABASE_VERSION = 17

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
            Tables.MD_WAITERS, Tables.MD_HEADERS, Tables.MD_FOOTERS, Tables.MD_CAPTIONS, Tables.MD_LOGOS,
            Tables.MD_QR,
            Tables.MD_APP_SETTINGS, Tables.MD_SUPPLIER, Tables.MD_BATCH_STOCK, Tables.MD_VERSION,
            Tables.MD_PRINTER, Tables.MD_OPERATING_PRINTER,
            Tables.TD_PURCHASE, Tables.TD_PURCHASE_RETURN, Tables.TD_WRITE_OFF, Tables.TD_BILLS,
            Tables.TD_BILL_ITEMS, Tables.TD_PAYMENTS, Tables.TD_SALE_RETURNS, Tables.TD_RETURN_ITEMS,
            Tables.TD_STOCK_TRANSACTIONS, Tables.TD_CUSTOMER_LEDGER, Tables.TD_ADVANCE_PAYMENTS,
            Tables.TD_KOT, Tables.TD_KOT_ITEMS, Tables.TD_BILL_PRINTS,
            Tables.TD_BILLS_DELETE, Tables.TD_BILL_ITEMS_DELETE
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

        /**
         * The shifts a shop runs, and who is on them.
         *
         * from_time / to_time are stored as "HH:mm" - the clock face rather than a
         * timestamp, because a shift is a shape of the day that repeats rather than
         * an event that happened once. A night shift whose to_time is earlier than
         * its from_time is one that crosses midnight, which the master accepts and
         * nothing here has to resolve: the shift is attached to a user, and it is the
         * user that a bill is counted under.
         */
        private const val SQL_CREATE_MD_SHIFTS = """
            CREATE TABLE IF NOT EXISTS md_shifts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                shift_name TEXT,
                from_time TEXT,
                to_time TEXT,
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

        // Master list of rate names (Rate 1 / Rate 2 / MRP …), chosen per product rate.
        private const val SQL_CREATE_MD_RATE_NAME = """
            CREATE TABLE IF NOT EXISTS md_rate_name (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                rate_name TEXT NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 1,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT
            )
        """

        /**
         * The extra-charges master: a shop's own additions to a bill - service,
         * packing, delivery - each a NAME and a PERCENTAGE, switched on and off one
         * at a time.
         *
         * A percentage rather than a flat amount because that is what these charges
         * are in practice: packing a large order costs more than packing a small one.
         * What it is a percentage OF is the bill's item lines before any tax - see
         * ChargeDao.
         *
         * is_enabled is deliberately separate from is_active. Active is whether the
         * row exists at all (deleting clears it); enabled is whether a charge that
         * exists is currently being applied. A shop that stops charging for delivery
         * for a month wants the row back afterwards with its rate intact, not retyped.
         */
        private const val SQL_CREATE_MD_CHARGES = """
            CREATE TABLE IF NOT EXISTS md_charges (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                charge_name TEXT NOT NULL,
                percentage REAL NOT NULL DEFAULT 0,
                is_enabled INTEGER NOT NULL DEFAULT 1,
                is_active INTEGER NOT NULL DEFAULT 1,
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
                sku TEXT,
                brand TEXT,
                hsn_code TEXT,
                stock_alert_qty REAL DEFAULT 0,
                bar_code TEXT,
                category_id INTEGER,
                product_image BLOB,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(store_id) REFERENCES md_registration(store_id),
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
                sku TEXT,
                batch_no TEXT,
                rate_name TEXT,
                rate_name_id INTEGER,
                rate REAL,
                unit_id INTEGER,
                cgst_rate REAL,
                sgst_rate REAL,
                igst_rate REAL,
                vat_rate REAL,
                discount REAL DEFAULT 0,
                discount_type TEXT CHECK(discount_type IN ('P','A')),
                sale_price REAL,
                sell_price REAL,
                purchase_price REAL,
                "default" INTEGER DEFAULT 0,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT
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
                header_type TEXT CHECK(header_type IN ('BILL','KOT')),
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT
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
                footer_type TEXT CHECK(footer_type IN ('BILL','KOT')),
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT
            )
        """

        /**
         * Captions are header/footer lines by another name - same columns, same
         * limits - but keyed to what the slip *is* rather than where on it the line
         * sits: an ordinary bill, a credit bill, or a reprint of one already issued.
         */
        private const val SQL_CREATE_MD_CAPTIONS = """
            CREATE TABLE IF NOT EXISTS md_captions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                caption_number INTEGER CHECK(caption_number BETWEEN 1 AND 10),
                caption_text TEXT,
                font_size TEXT CHECK(font_size IN ('SMALL','MEDIUM','BIG','EXTRA_LARGE')),
                is_bold INTEGER NOT NULL DEFAULT 0,
                is_enabled INTEGER NOT NULL DEFAULT 1,
                caption_type TEXT CHECK(caption_type IN ('BILL','DUPLICATE','CREDIT')),
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT
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
                mfg_date TEXT,
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

        // Restaurant sections/floors. price_list_id references a product rate tier
        // (md_product_rates.rate_name — Rate 1 / Rate 2 …) shown in a dropdown.
        private const val SQL_CREATE_MD_SECTION = """
            CREATE TABLE IF NOT EXISTS md_section (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                outlet_id INTEGER,
                section_name TEXT NOT NULL,
                no_of_tables INTEGER DEFAULT 0,
                price_list_id INTEGER,
                service_charge REAL DEFAULT 0,
                is_active INTEGER NOT NULL DEFAULT 1,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT
            )
        """

        // Restaurant tables, each belonging to a section (md_section).
        private const val SQL_CREATE_MD_TABLE = """
            CREATE TABLE IF NOT EXISTS md_table (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                outlet_id INTEGER,
                section_id INTEGER,
                no_of_tables INTEGER DEFAULT 0,
                from_table_no INTEGER,
                to_table_no INTEGER,
                table_code TEXT,
                floor_no TEXT,
                seating_capacity INTEGER DEFAULT 0,
                table_status TEXT CHECK(table_status IN
                    ('Available','Occupied','Reserved','Cleaning','Billing','Blocked','KOT Printed')) DEFAULT 'Available',
                waiter_id INTEGER,
                remarks TEXT,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(section_id) REFERENCES md_section(id),
                FOREIGN KEY(waiter_id) REFERENCES md_waiters(id)
            )
        """

        // Assigned waiters (restaurant): only the waiter id and name are kept.
        private const val SQL_CREATE_TD_ASSIGN_WAITER = """
            CREATE TABLE IF NOT EXISTS td_assign_waiter (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                outlet_id INTEGER,
                waiter_id INTEGER,
                waiter_name TEXT,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(waiter_id) REFERENCES md_waiters(id)
            )
        """

        // A running (open) table order — the live bill before payment.
        private const val SQL_CREATE_TD_RUNNING_ORDER = """
            CREATE TABLE IF NOT EXISTS td_running_order (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                outlet_id INTEGER,
                table_code TEXT,
                section TEXT,
                waiter_id INTEGER,
                order_type TEXT,
                customer_phone TEXT,
                cashier TEXT,
                order_note TEXT,
                merged_tables TEXT,
                status TEXT NOT NULL DEFAULT 'RUNNING',
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT
            )
        """

        // Items on a running order; kot_printed flags what has gone to the kitchen.
        private const val SQL_CREATE_TD_RUNNING_ORDER_ITEMS = """
            CREATE TABLE IF NOT EXISTS td_running_order_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                running_order_id INTEGER,
                product_id INTEGER,
                product_name TEXT,
                quantity REAL DEFAULT 1,
                rate REAL DEFAULT 0,
                cgst_rate REAL DEFAULT 0,
                sgst_rate REAL DEFAULT 0,
                vat_rate REAL DEFAULT 0,
                kot_printed INTEGER NOT NULL DEFAULT 0,
                kot_qty REAL NOT NULL DEFAULT 0,
                created_at TEXT DEFAULT (datetime('now','localtime')),
                FOREIGN KEY(running_order_id) REFERENCES td_running_order(id)
            )
        """

        // Individual tables belonging to a table allocation (md_table).
        private const val SQL_CREATE_MD_TABLE_UNIT = """
            CREATE TABLE IF NOT EXISTS md_table_unit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                table_id INTEGER,
                section_id INTEGER,
                table_code TEXT,
                floor_no TEXT,
                seating_capacity INTEGER DEFAULT 0,
                table_status TEXT CHECK(table_status IN
                    ('Available','Occupied','Reserved','Cleaning','Billing','Blocked','KOT Printed')) DEFAULT 'Available',
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(table_id) REFERENCES md_table(id),
                FOREIGN KEY(section_id) REFERENCES md_section(id)
            )
        """

        // Split sub-tables: parts of a table (101 A, 101 B, …) created on Table Split.
        private const val SQL_CREATE_MD_SUBTABLE = """
            CREATE TABLE IF NOT EXISTS md_subtable (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
                table_id INTEGER,
                parent_code TEXT,
                sub_code TEXT,
                suffix TEXT,
                table_status TEXT CHECK(table_status IN
                    ('Available','Occupied','Reserved','Cleaning','Billing','Blocked','KOT Printed')) DEFAULT 'Occupied',
                created_at TEXT DEFAULT (datetime('now','localtime')),
                modified_at TEXT,
                created_by TEXT,
                modified_by TEXT,
                FOREIGN KEY(table_id) REFERENCES md_table(id)
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
                bill_seq_no INTEGER,
                bill_date TEXT,
                bill_date_time TEXT,
                customer_id INTEGER,
                operator_id INTEGER,
                waiter_id INTEGER,
                table_number TEXT,
                table_section TEXT,
                order_type TEXT,
                service_charge_amount REAL DEFAULT 0,
                bill_type TEXT CHECK(bill_type IN ('CASH','CREDIT','CARD','ONLINE','VOID')),
                settings_snapshot TEXT,
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
                store_id INTEGER,
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

        /**
         * A deleted bill, kept exactly as it was billed.
         *
         * The same columns as td_bills so a row moves across whole, but without its
         * foreign keys: a deleted bill may outlive the customer or operator it named,
         * and refusing the archive because a master row has since gone would leave
         * the till unable to delete the bill at all.
         */
        private const val SQL_CREATE_TD_BILLS_DELETE = """
            CREATE TABLE IF NOT EXISTS td_bills_delete (
                receipt_no INTEGER PRIMARY KEY,
                store_id INTEGER,
                outlet_id INTEGER,
                bill_number TEXT,
                bill_seq_no INTEGER,
                bill_date TEXT,
                bill_date_time TEXT,
                customer_id INTEGER,
                operator_id INTEGER,
                waiter_id INTEGER,
                table_number TEXT,
                table_section TEXT,
                order_type TEXT,
                service_charge_amount REAL DEFAULT 0,
                bill_type TEXT CHECK(bill_type IN ('CASH','CREDIT','CARD','ONLINE','VOID')),
                settings_snapshot TEXT,
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
                modified_by TEXT
            )
        """

        /**
         * A deleted bill's lines. No foreign key to td_bills for the plain reason
         * that the bill is no longer there - it is in td_bills_delete beside these.
         */
        private const val SQL_CREATE_TD_BILL_ITEMS_DELETE = """
            CREATE TABLE IF NOT EXISTS td_bill_items_delete (
                id INTEGER PRIMARY KEY,
                store_id INTEGER,
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
                modified_by TEXT
            )
        """

        private const val SQL_CREATE_TD_PAYMENTS = """
            CREATE TABLE IF NOT EXISTS td_payments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER,
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
                stock_flow TEXT CHECK(stock_flow IN ('IN','OUT')),
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
                receipt_number TEXT,
                advance_amount REAL,
                remaining_balance REAL,
                payment_date TEXT,
                payment_mode TEXT,
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
                running_order_id INTEGER,
                kot_number TEXT,
                table_number TEXT,
                waiter_id INTEGER,
                kot_date TEXT,
                kot_time TEXT,
                status TEXT CHECK(status IN ('OPEN','RECEIVED','PREPARING','READY','SERVED','CLOSED','CANCELLED')) DEFAULT 'OPEN',
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
                status TEXT CHECK(status IN ('PENDING','COMPLETE','CANCELLED','PREPARED','DELIVERED')) DEFAULT 'PENDING',
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

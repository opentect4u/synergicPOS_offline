package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.GeneralSettingsDao

/**
 * Fingerprints of what a sale screen's product grid is built from - so a screen can
 * keep the catalogue it read and only read it again when this says it moved.
 *
 * ## Why the sale screens hold on to their catalogue
 *
 * Reading the catalogue is a pass over every product and every rate. At 5,000 products
 * that is seconds, and it used to happen every time a sale screen was opened - the
 * header's Sale button builds a fresh one each time - and on the grocery till after
 * every sale and every Hold as well. Nearly all of those reads found exactly what the
 * last one had. The screens now keep one catalogue for the session (see their
 * companion caches) and ask this first.
 *
 * ## Two fingerprints, because they change at different rates
 *
 * [of] is the catalogue itself - products, rates, categories, units, regional names
 * and the settings that change how they are read. It moves when someone edits the
 * masters, which is rare. [stock] is what is on hand, which moves on every sale. A
 * screen whose stock fingerprint changed but whose catalogue did not re-reads the
 * counts alone, not the whole menu.
 *
 * Both are aggregate queries - counts, highest ids, last edits, running totals - that
 * read no row, so they cost microseconds against the read they save.
 */
object CatalogueSignature {

    /**
     * Which login the sale screens' kept catalogues belong to. A catalogue is only
     * reused within the session that read it; [newSession], on logout, makes every
     * one held so far stale, so the next login reads its own from nothing.
     */
    @Volatile var session: Int = 0
        private set

    fun newSession() { session++ }

    /** Everything the grid shows except stock. Any master edit moves it. */
    fun of(ctx: Context): String {
        val db = DatabaseHelper.getInstance(ctx).readableDatabase
        fun one(sql: String): String = runCatching {
            db.rawQuery(sql, null).use { c ->
                if (!c.moveToFirst()) "" else (0 until c.columnCount).joinToString(",") { c.getString(it).orEmpty() }
            }
        }.getOrDefault("?")
        return listOf(
            one("SELECT count(*), max(id), max(modified_at), max(created_at), " +
                "total(length(product_name)), total(category_id), " +
                "total(COALESCE(length(product_image), 0)), total(length(COALESCE(bar_code, ''))), " +
                "total(length(COALESCE(hsn_code, ''))), " +
                "group_concat(DISTINCT availability) FROM md_products"),
            one("SELECT count(*), max(id), max(modified_at), total(rate), total(unit_id), " +
                "total(cgst_rate + sgst_rate + igst_rate + vat_rate), total(discount), " +
                "total(\"default\") FROM md_product_rates"),
            one("SELECT count(*), max(id), max(modified_at), total(length(category_name)) FROM md_category"),
            one("SELECT count(*), max(id), max(modified_at), total(fraction_flag) FROM md_units"),
            one("SELECT count(*), max(id), max(modified_at), total(length(regional_name)) FROM md_product_names"),
            SettingsCache.value(ctx, "G", "Item Rate").orEmpty(),
            runCatching { GeneralSettingsDao.productSort(ctx).toString() }.getOrDefault(""),
            runCatching { GeneralSettingsDao.isStockEnabled(ctx).toString() }.getOrDefault(""),
            AppLanguage.of(ctx).toString()
        ).joinToString("|")
    }

    /** What is on hand, or "" while stock is not tracked (then it never moves). */
    fun stock(ctx: Context): String {
        if (!runCatching { GeneralSettingsDao.isStockEnabled(ctx) }.getOrDefault(false)) return ""
        val db = DatabaseHelper.getInstance(ctx).readableDatabase
        // The alert settings decide which counts read as LOW, so they are part of it.
        val alert = runCatching {
            GeneralSettingsDao(ctx).load().let { "${it.stockAlert}:${it.stockAlertQty}" }
        }.getOrDefault("")
        return runCatching {
            db.rawQuery(
                "SELECT count(*), total(current_quantity) FROM ${DatabaseHelper.Tables.MD_BATCH_STOCK}", null
            ).use { c -> if (c.moveToFirst()) "${c.getString(0)},${c.getString(1)},$alert" else "" }
        }.getOrDefault("?")
    }
}

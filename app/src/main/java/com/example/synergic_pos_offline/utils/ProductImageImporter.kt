package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import com.example.synergic_pos_offline.database.DatabaseHelper
import java.io.File
import java.io.InputStream

/**
 * Puts a folder of pictures onto the products they belong to, in one go.
 *
 * ## How a picture finds its product
 *
 * BY ITS FILE NAME, which is the product's own id in `md_products`. `41.jpg` is the
 * picture for product 41. Nothing else about the file is read - not its contents, not
 * a sidecar list, not the order it happens to sit in the folder - so the shop can
 * name its pictures on a computer, drop them on the tablet, and be done.
 *
 * The id is what the Excel upload already hands back: the exported sheet carries
 * PRODUCT_ID on every row, so the operator has the numbers in front of them before
 * they rename a single file.
 *
 * Anything that is not a number is left alone and REPORTED rather than guessed at -
 * a `logo.png` in the same folder is not a product and must not be dropped on one,
 * and a `41 (1).jpg` that a phone renamed is exactly the case where a guess would put
 * the wrong picture on the wrong product.
 *
 * ## Why the folder is picked rather than typed
 *
 * The operator names the folder through the system's own folder picker, and this
 * reads it through the tree it hands back. That is not a preference: this app holds
 * no storage permission at all, and since Android 10 an app cannot open a path like
 * `/sdcard/ProductImages` without one - `listFiles()` simply returns null. The picker
 * grants access to the one folder chosen, needs no permission, and the grant can be
 * held on to so the folder only has to be named once.
 *
 * A plain filesystem path is still tried first where one is given - see [Source.Dir] -
 * because it costs nothing and it works for the folders the app CAN read.
 *
 * ## What it writes
 *
 * `md_products.product_image`, the same BLOB the Products screen writes, at the same
 * size - see [ProductImages]. Only that column: a picture is not a reason to touch a
 * product's price, its stock or its name.
 */
object ProductImageImporter {

    /** Where the pictures are being read from. */
    sealed class Source {
        /** A folder the operator picked, as the system's own document tree. */
        data class Tree(val uri: Uri) : Source()

        /** A plain path, for the folders this app is actually allowed to open. */
        data class Dir(val path: String) : Source()
    }

    /** One file in the folder, and what this makes of its name. */
    private data class Candidate(
        val name: String,
        /** The product id its name spells, or null where the name is not a number. */
        val productId: Long?,
        val open: () -> InputStream?
    )

    /**
     * What the folder holds, worked out before anything is written.
     *
     * Read first and shown to the operator, the same way the sheet upload previews
     * its rows: this overwrites pictures that are already on products, and a folder
     * with the wrong names in it should be caught while Cancel is still on screen.
     */
    data class Preview(
        /** Files in the folder that are images at all. */
        val images: Int,
        /** Images whose name names a product this till actually has. */
        val matched: Int,
        /** Matched products that already carry a picture - these get replaced. */
        val replacing: Int,
        /** Names that are not a number, or a number no product here uses. */
        val unmatchedNames: List<String>,
        /** Files that are not images - left alone entirely. */
        val skipped: Int,
        /** Why nothing could be read, where that is the answer. */
        val error: String? = null
    ) {
        val hasWork: Boolean get() = matched > 0
        val unmatched: Int get() = unmatchedNames.size
    }

    /** What the import actually did. */
    data class Result(
        val updated: Int,
        /** Images that matched a product but could not be decoded or stored. */
        val failed: Int,
        val unmatched: Int,
        val skipped: Int,
        val error: String? = null
    )

    /** How many unmatched names are listed before the rest are counted. */
    const val NAMES_LISTED = 8

    /**
     * Reads the folder and says what an import would do, without doing any of it.
     *
     * Cheap on purpose: a file's bytes are opened only far enough to learn its size,
     * never decoded. A folder of four hundred photographs must not be decoded twice
     * over just to put a number in a dialog.
     */
    fun preview(context: Context, source: Source): Preview {
        val files = try {
            list(context, source)
        } catch (e: Exception) {
            return Preview(0, 0, 0, emptyList(), 0, error = reason(e))
        }
        if (files.isEmpty()) {
            return Preview(0, 0, 0, emptyList(), 0, error = "That folder holds no files.")
        }

        val images = files.filter { looksLikeImage(it.name) }
        val known = productIds(context)
        val matched = images.filter { it.productId != null && it.productId in known }
        val unmatched = images.filter { it.productId == null || it.productId !in known }

        return Preview(
            images = images.size,
            matched = matched.size,
            replacing = countWithImage(context, matched.mapNotNull { it.productId }),
            unmatchedNames = unmatched.map { it.name },
            skipped = files.size - images.size
        )
    }

    /**
     * Writes every matched picture onto its product.
     *
     * BLOCKING - it decodes and re-encodes every image in the folder, so it belongs
     * on a worker thread.
     *
     * One product at a time rather than one transaction around the lot: a single
     * unreadable file in a folder of two hundred should cost that one picture, not
     * the other hundred and ninety-nine. Nothing here can leave a product half
     * written - a picture is one column of one row - so there is no consistency to
     * protect by rolling the rest back.
     */
    fun import(context: Context, source: Source): Result {
        val files = try {
            list(context, source)
        } catch (e: Exception) {
            return Result(0, 0, 0, 0, error = reason(e))
        }

        val images = files.filter { looksLikeImage(it.name) }
        val known = productIds(context)
        val db = DatabaseHelper.getInstance(context).writableDatabase

        var updated = 0
        var failed = 0
        var unmatched = 0
        images.forEach { file ->
            val id = file.productId
            if (id == null || id !in known) {
                unmatched++
                return@forEach
            }
            val bytes = try {
                file.open().use { input ->
                    val bitmap = BitmapFactory.decodeStream(input ?: return@use null)
                    bitmap?.let { ProductImages.compress(it) }
                }
            } catch (_: Exception) {
                null
            }
            if (bytes == null) {
                failed++
                return@forEach
            }
            val stored = runCatching {
                db.execSQL(
                    "UPDATE ${DatabaseHelper.Tables.MD_PRODUCTS} SET product_image = ? WHERE id = ?",
                    arrayOf<Any>(bytes, id)
                )
            }.isSuccess
            if (stored) updated++ else failed++
        }

        android.util.Log.i(
            "ProductImages",
            "folder import: $updated updated, $failed failed, $unmatched unmatched, " +
                "${files.size - images.size} not images"
        )
        return Result(updated, failed, unmatched, files.size - images.size)
    }

    // ---- Reading the folder ------------------------------------------------------

    /** Every file directly inside [source]. Subfolders are not descended into. */
    private fun list(context: Context, source: Source): List<Candidate> = when (source) {
        is Source.Tree -> listTree(context, source.uri)
        is Source.Dir -> listDir(source.path)
    }

    /**
     * The children of a picked folder, through the document tree it was granted as.
     *
     * DocumentsContract rather than the DocumentFile library: this needs one query
     * and a URI built per row, and pulling in a dependency to wrap two calls would be
     * a dependency to keep up to date for no reading it makes easier.
     */
    private fun listTree(context: Context, treeUri: Uri): List<Candidate> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val found = mutableListOf<Candidate>()
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                // A folder inside the folder is not a picture. Left alone rather than
                // descended into: the operator named ONE folder, and quietly reaching
                // into others is not what they asked for.
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                found.add(
                    Candidate(name, idFromName(name)) {
                        context.contentResolver.openInputStream(uri)
                    }
                )
            }
        }
        return found
    }

    /** The children of a plain path, where this app is allowed to read one. */
    private fun listDir(path: String): List<Candidate> {
        val dir = File(path)
        if (!dir.isDirectory) throw IllegalArgumentException("$path is not a folder this app can open")
        val files = dir.listFiles()
            ?: throw IllegalStateException("$path cannot be read - pick the folder instead")
        return files.filter { it.isFile }.map { file ->
            Candidate(file.name, idFromName(file.name)) { file.inputStream() }
        }
    }

    // ---- Names and products ------------------------------------------------------

    /**
     * The product id [name] spells, or null where it does not spell one.
     *
     * The name up to the LAST dot, which has to be the whole of it and has to be
     * digits. `41.jpg` is product 41; `41 (1).jpg`, `41-front.jpg` and `logo.png` are
     * none of them product 41, and are reported rather than assumed - a phone that
     * renamed a duplicate download is exactly how the wrong picture would otherwise
     * land on the right product.
     */
    private fun idFromName(name: String): Long? =
        name.substringBeforeLast('.', name).takeIf { it.isNotEmpty() && it.all { ch -> ch.isDigit() } }
            ?.toLongOrNull()

    /** Whether the file's extension says it is a picture this can decode. */
    private fun looksLikeImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_TYPES

    private val IMAGE_TYPES = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")

    /** Every product id on this till, to match names against. */
    private fun productIds(context: Context): Set<Long> {
        val ids = HashSet<Long>()
        DatabaseHelper.getInstance(context).readableDatabase
            .rawQuery("SELECT id FROM ${DatabaseHelper.Tables.MD_PRODUCTS}", null)
            .use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        return ids
    }

    /** How many of [ids] already carry a picture - the ones about to be replaced. */
    private fun countWithImage(context: Context, ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        return DatabaseHelper.getInstance(context).readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${DatabaseHelper.Tables.MD_PRODUCTS} " +
                "WHERE product_image IS NOT NULL AND id IN (${ids.joinToString(",")})",
            null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** An exception as something the operator can act on. */
    private fun reason(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}

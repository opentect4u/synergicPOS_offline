package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.CategoryDao
import com.example.synergic_pos_offline.utils.ImageUtils
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import java.io.File

/**
 * "Category / Department" management screen — a concrete [DataTableFragment]
 * backed by the [CategoryDao] SQLite table.
 *
 * The bespoke Add/Edit popup supports:
 *  - a category image captured from the **camera or gallery**, stored as a BLOB,
 *  - an auto-generated, read-only department code derived from the row id,
 *  - a reminder of the last generated code value.
 */
class CategoryDepartmentFragment : DataTableFragment() {

    override val screenTitle = "Category / Department"

    // Table columns. Cell layout per row: [imageState, code, name].
    override val columns = listOf("Image", "Dept Code", "Department Name")

    private companion object {
        const val COL_IMAGE = 0
        const val COL_CODE = 1
        const val COL_NAME = 2
    }

    private val dao: CategoryDao by lazy { CategoryDao(requireContext()) }

    /** Decoded list thumbnails, keyed by row id (DB id as string). */
    private val thumbCache = mutableMapOf<String, Bitmap?>()

    // Live handoff state for the currently open dialog's image slot.
    private var pendingImageView: ImageView? = null
    private var pendingImageBytes: ByteArray? = null
    private var cameraUri: Uri? = null

    // ---- Image capture launchers (registered before STARTED) --------------

    private val pickGallery: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { onImagePicked(it) }
        }

    private val takePhoto: ActivityResultLauncher<Uri> =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) cameraUri?.let { onImagePicked(it) }
        }

    // ---- Data --------------------------------------------------------------

    override val thumbnailColumn: Int? = COL_IMAGE

    override fun loadThumbnail(row: DataRow): Bitmap? = thumbCache[row.id]

    override fun loadRows(): MutableList<DataRow> {
        thumbCache.clear()
        val categories = dao.getAll()
        for (c in categories) {
            thumbCache[c.id.toString()] = c.image?.let { ImageUtils.decodeThumb(it) }
        }
        return categories.map { it.toRow() }.toMutableList()
    }

    private fun CategoryDao.Category.toRow(): DataRow =
        DataRow(id.toString(), listOf(if (hasImage) "Uploaded" else "Not set", code, name))

    // ---- Custom Add / Edit popups -----------------------------------------

    override fun onAddRow() = showCategoryDialog(null)

    override fun onEditRow(row: DataRow) = showCategoryDialog(row)

    override fun onRowsDeleted(ids: Set<String>) {
        dao.delete(ids.mapNotNull { it.toLongOrNull() })
    }

    /** A category still holding products cannot be removed - name the offenders. */
    override fun deleteBlockedReason(ids: Set<String>): String? {
        val inUse = dao.namesInUse(ids.mapNotNull { it.toLongOrNull() })
        return when {
            inUse.isEmpty() -> null
            inUse.size == 1 -> "\"${inUse.first()}\" still has products. Move or delete them first."
            else -> "${inUse.size} categories still have products: ${inUse.joinToString(", ")}"
        }
    }

    /** Opens a large preview of the row's stored image. */
    override fun onThumbnailClick(row: DataRow) {
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_image_preview, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val iv = view.findViewById<ImageView>(R.id.ivPreview)
        val tvEmpty = view.findViewById<TextView>(R.id.tvPreviewEmpty)
        val btnClose = view.findViewById<MaterialButton>(R.id.btnPreviewClose)
        view.findViewById<TextView>(R.id.tvPreviewName).text =
            row.cells.getOrNull(COL_NAME).orEmpty()
        view.findViewById<TextView>(R.id.tvPreviewCode).text =
            row.cells.getOrNull(COL_CODE).orEmpty()

        val bytes = row.id.toLongOrNull()?.let { dao.getImage(it) }
        if (bytes != null) {
            iv.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        } else {
            iv.visibility = android.view.View.GONE
            tvEmpty.visibility = android.view.View.VISIBLE
        }

        btnClose.backgroundTintList = ColorStateList.valueOf(accent)
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.CENTER)
        }
    }

    /**
     * Shows the Category/Department popup. When [existing] is null the dialog is
     * in "Add" mode and previews the next dept code; otherwise it edits the given
     * row while keeping its original code and pre-loading its stored image.
     */
    private fun showCategoryDialog(existing: DataRow?) {
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_category_department, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val cardImage = view.findViewById<MaterialCardView>(R.id.cardImage)
        val ivImage = view.findViewById<ImageView>(R.id.ivCategoryImage)
        val etCode = view.findViewById<TextInputEditText>(R.id.etDeptCode)
        val etName = view.findViewById<TextInputEditText>(R.id.etDeptName)
        val tvLast = view.findViewById<TextView>(R.id.tvLastGenerated)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnFormNegative)

        pendingImageView = ivImage
        pendingImageBytes = existing?.id?.toLongOrNull()?.let { dao.getImage(it) }
        pendingImageBytes?.let { showPreview(ivImage, it) }

        val code = existing?.cells?.getOrNull(COL_CODE).orEmpty()
            .ifBlank { CategoryDao.formatCode(dao.nextId()) }
        tvTitle.text = if (existing == null) "Add Category / Department" else "Edit Department"
        etCode.setText(code)
        etName.setText(existing?.cells?.getOrNull(COL_NAME).orEmpty())
        tvLast.text = "Last generated: ${dao.lastId()?.let(CategoryDao::formatCode) ?: "—"}"
        btnSave.text = if (existing == null) "Add" else "Update"

        cardImage.setOnClickListener { showImageSourceChooser() }

        ThemeManager.applyTheme(view)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent)
        // ThemeManager fills every MaterialButton's background; restore the
        // outlined (border) look for the negative/Cancel button.
        btnCancel.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        btnCancel.setTextColor(accent)
        btnCancel.strokeColor = ColorStateList.valueOf(accent)

        btnSave.setOnClickListener {
            val name = etName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                etName.error = "Name is required"
                return@setOnClickListener
            }
            val image = pendingImageBytes
            val state = if (image != null) "Uploaded" else "Not set"

            val thumb = image?.let { ImageUtils.decodeThumb(it) }

            if (existing == null) {
                val id = dao.insert(name, image)
                if (id == -1L) { toast("Save failed"); return@setOnClickListener }
                dialog.dismiss()
                thumbCache[id.toString()] = thumb
                addRow(DataRow(id.toString(), listOf(state, CategoryDao.formatCode(id), name)))
                toast("Added ${CategoryDao.formatCode(id)}")
            } else {
                dao.update(existing.id.toLong(), name, image)
                dialog.dismiss()
                thumbCache[existing.id] = thumb
                updateRow(existing.id, listOf(state, code, name))
                toast("Updated $code")
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // ---- Image source selection -------------------------------------------

    private fun showImageSourceChooser() {
        AlertDialog.Builder(requireContext())
            .setTitle("Category Image")
            .setItems(arrayOf("Take Photo", "Choose from Gallery")) { _, which ->
                if (which == 0) launchCamera() else pickGallery.launch("image/*")
            }
            .setNegativeButton("Cancel", null)
            .create()
            .also { it.setCanceledOnTouchOutside(false); it.show() }
    }

    private fun launchCamera() {
        val ctx = requireContext()
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "cat_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        cameraUri = uri
        takePhoto.launch(uri)
    }

    private fun onImagePicked(uri: Uri) {
        val bytes = ImageUtils.uriToJpegBytes(requireContext(), uri)
        if (bytes == null) {
            toast("Couldn't load image")
            return
        }
        pendingImageBytes = bytes
        pendingImageView?.let { showPreview(it, bytes) }
    }

    /** Fills the circular slot with an actual image (dropping the placeholder tint). */
    private fun showPreview(target: ImageView, bytes: ByteArray) {
        target.setPadding(0, 0, 0, 0)
        target.imageTintList = null
        target.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }
}

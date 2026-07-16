package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.LogoDao
import com.example.synergic_pos_offline.database.LogoDao.LogoType
import com.example.synergic_pos_offline.utils.ImageUtils
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import java.io.File

/**
 * "Bill Header Footer Logo" management screen — a concrete [DataTableFragment]
 * backed by the [LogoDao] (md_logos, BILL_HEADER / BILL_FOOTER).
 *
 * Each row is a logo image (captured from camera or gallery, stored as a BLOB)
 * tagged with a type. The image column renders as a tappable thumbnail that
 * opens a full-size preview.
 */
class BillLogoFragment : DataTableFragment() {

    override val screenTitle = "Bill Header Footer Logo"

    // Table columns. Cell layout per row: [imageState, typeLabel].
    override val columns = listOf("Logo", "Type")

    private companion object {
        const val COL_IMAGE = 0
        const val COL_TYPE = 1
    }

    /** The logo slots this screen owns. */
    private val myTypes = listOf(LogoType.BILL_HEADER, LogoType.BILL_FOOTER)

    private val dao: LogoDao by lazy { LogoDao(requireContext()) }

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
        val logos = dao.getAll(myTypes)
        for (l in logos) thumbCache[l.id.toString()] = l.image?.let { ImageUtils.decodeThumb(it) }
        return logos.map { it.toRow() }.toMutableList()
    }

    private fun LogoDao.Logo.toRow(): DataRow =
        DataRow(id.toString(), listOf(if (hasImage) "Uploaded" else "Not set", type.label))

    // ---- Custom Add / Edit popups -----------------------------------------

    override fun onAddRow() = showLogoDialog(null)

    override fun onEditRow(row: DataRow) = showLogoDialog(row)

    override fun onRowsDeleted(ids: Set<String>) {
        dao.delete(ids.mapNotNull { it.toLongOrNull() })
    }

    /** Opens a large preview of the row's stored logo image. */
    override fun onThumbnailClick(row: DataRow) {
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_image_preview, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val iv = view.findViewById<ImageView>(R.id.ivPreview)
        val tvEmpty = view.findViewById<TextView>(R.id.tvPreviewEmpty)
        val btnClose = view.findViewById<MaterialButton>(R.id.btnPreviewClose)
        view.findViewById<TextView>(R.id.tvPreviewName).text = row.cells.getOrNull(COL_TYPE).orEmpty()
        view.findViewById<TextView>(R.id.tvPreviewCode).text = "Bill Logo"

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

    private fun showLogoDialog(existing: DataRow?) {
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_logo, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val cardImage = view.findViewById<MaterialCardView>(R.id.cardImage)
        val ivImage = view.findViewById<ImageView>(R.id.ivLogoImage)
        val actvType = view.findViewById<MaterialAutoCompleteTextView>(R.id.actvLogoType)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnFormNegative)

        pendingImageView = ivImage
        pendingImageBytes = existing?.id?.toLongOrNull()?.let { dao.getImage(it) }
        pendingImageBytes?.let { showPreview(ivImage, it) }

        actvType.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, myTypes.map { it.label }))
        tvTitle.text = if (existing == null) "Add Logo" else "Edit Logo"
        actvType.setText(
            existing?.cells?.getOrNull(COL_TYPE) ?: myTypes.first().label, false
        )
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
            val type = LogoType.fromLabel(actvType.text?.toString()) ?: myTypes.first()
            val image = pendingImageBytes
            if (image == null) {
                toast("Please upload a logo image")
                return@setOnClickListener
            }
            val thumb = ImageUtils.decodeThumb(image)

            if (existing == null) {
                val id = dao.insert(type, image)
                if (id == -1L) { toast("Save failed"); return@setOnClickListener }
                dialog.dismiss()
                thumbCache[id.toString()] = thumb
                addRow(DataRow(id.toString(), listOf("Uploaded", type.label)))
                toast("Logo added")
            } else {
                dao.update(existing.id.toLong(), type, image)
                dialog.dismiss()
                thumbCache[existing.id] = thumb
                updateRow(existing.id, listOf("Uploaded", type.label))
                toast("Logo updated")
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.CENTER)
        }
    }

    // ---- Image source selection -------------------------------------------

    private fun showImageSourceChooser() {
        AlertDialog.Builder(requireContext())
            .setTitle("Logo Image")
            .setItems(arrayOf("Take Photo", "Choose from Gallery")) { _, which ->
                if (which == 0) launchCamera() else pickGallery.launch("image/*")
            }
            .show()
    }

    private fun launchCamera() {
        val ctx = requireContext()
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "logo_${System.currentTimeMillis()}.jpg")
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

    /** Fills the square slot with an actual image (dropping the placeholder tint). */
    private fun showPreview(target: ImageView, bytes: ByteArray) {
        target.setPadding(0, 0, 0, 0)
        target.imageTintList = null
        target.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }
}

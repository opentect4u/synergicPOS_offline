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
 * The logo management screen, for whichever document carries them — a
 * [DataTableFragment] backed by [LogoDao] (md_logos).
 *
 * Each row is a logo image (captured from camera or gallery, cropped, stored as a
 * BLOB) tagged with a slot. The image column renders as a tappable thumbnail that
 * opens a full-size preview.
 *
 * ## One screen, two documents
 *
 * The receipt and the kitchen ticket keep their own logos, but managing them is the
 * same job - the same table, the same camera/gallery pick, the same crop step, the same
 * preview. So there is one screen and the subclasses supply only what differs: the
 * [screenTitle], the [myTypes] slots they own, and the word the preview card is
 * captioned with.
 *
 * The DAO was already built for this - it takes the subset of types a caller owns - so
 * nothing below it had to change. See [BillLogoFragment] and [KotLogoFragment].
 */
abstract class LogoFragment : DataTableFragment() {

    // Table columns. Cell layout per row: [imageState, typeLabel].
    override val columns = listOf("Logo", "Type")

    private companion object {
        const val COL_IMAGE = 0
        const val COL_TYPE = 1
    }

    /** The logo slots this screen owns - the rest of md_logos is another screen's. */
    protected abstract val myTypes: List<LogoType>

    /** What the full-size preview card calls these, e.g. "Bill Logo". */
    protected abstract val previewCaption: String

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
        val ctx = com.example.synergic_pos_offline.utils.FixedFontScale.wrap(requireContext())
        val accent = ThemeManager.getThemeColor(ctx)

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_image_preview, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT); setGravity(android.view.Gravity.CENTER) }

        val iv = view.findViewById<ImageView>(R.id.ivPreview)
        val tvEmpty = view.findViewById<TextView>(R.id.tvPreviewEmpty)
        val btnClose = view.findViewById<MaterialButton>(R.id.btnPreviewClose)
        view.findViewById<TextView>(R.id.tvPreviewName).text = row.cells.getOrNull(COL_TYPE).orEmpty()
        view.findViewById<TextView>(R.id.tvPreviewCode).text = previewCaption

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
        val ctx = com.example.synergic_pos_offline.utils.FixedFontScale.wrap(requireContext())
        val accent = ThemeManager.getThemeColor(ctx)

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_logo, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT); setGravity(android.view.Gravity.CENTER) }

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val cardImage = view.findViewById<MaterialCardView>(R.id.cardImage)
        val ivImage = view.findViewById<ImageView>(R.id.ivLogoImage)
        val actvType = view.findViewById<MaterialAutoCompleteTextView>(R.id.actvLogoType)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnFormNegative)

        pendingImageView = ivImage
        pendingImageBytes = existing?.id?.toLongOrNull()?.let { dao.getImage(it) }
        pendingImageBytes?.let { showPreview(ivImage, it) }

        actvType.setAdapter(com.example.synergic_pos_offline.utils.Dropdowns.adapter(ctx, myTypes.map { it.label }))
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
            .setNegativeButton("Cancel", null)
            .create()
            .also { it.setCanceledOnTouchOutside(false); it.show() }
    }

    private fun launchCamera() {
        val ctx = com.example.synergic_pos_offline.utils.FixedFontScale.wrap(requireContext())
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "logo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        cameraUri = uri
        takePhoto.launch(uri)
    }

    private fun onImagePicked(uri: Uri) {
        val bmp = ImageUtils.uriToBitmap(requireContext(), uri)
        if (bmp == null) {
            toast("Couldn't load image")
            return
        }
        showCropDialog(bmp)
    }

    /**
     * Crop step before a logo is accepted: the picked/captured image is shown in a crop
     * frame, and the selection is stored at a standard size (white-flattened, longest
     * edge [ImageUtils.STORE_MAX_DIM]) so it prints properly on the bill.
     */
    private fun showCropDialog(bmp: Bitmap) {
        val ctx = com.example.synergic_pos_offline.utils.FixedFontScale.wrap(requireContext())
        val accent = ThemeManager.getThemeColor(ctx)

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_crop, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.CENTER)
        }

        val cropView = view.findViewById<com.example.synergic_pos_offline.utils.CropImageView>(R.id.cropView)
        cropView.setImage(bmp)
        val btnCrop = view.findViewById<MaterialButton>(R.id.btnCropSave)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCropCancel)
        btnCrop.backgroundTintList = ColorStateList.valueOf(accent)
        btnCancel.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        btnCancel.setTextColor(accent)
        btnCancel.strokeColor = ColorStateList.valueOf(accent)

        btnCrop.setOnClickListener {
            val cropped = cropView.getCroppedBitmap(ImageUtils.STORE_MAX_DIM, ImageUtils.STORE_MAX_DIM)
            if (cropped == null) { toast("Crop failed"); return@setOnClickListener }
            val bytes = ImageUtils.bitmapToJpegBytes(cropped)
            pendingImageBytes = bytes
            pendingImageView?.let { showPreview(it, bytes) }
            dialog.dismiss()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    /** Fills the square slot with an actual image (dropping the placeholder tint). */
    private fun showPreview(target: ImageView, bytes: ByteArray) {
        target.setPadding(0, 0, 0, 0)
        target.imageTintList = null
        target.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }
}

/**
 * "Bill Header Footer Logo" - the images printed at the head and foot of the
 * customer's receipt.
 */
class BillLogoFragment : LogoFragment() {
    override val screenTitle = "Bill Header Footer Logo"
    override val myTypes = listOf(LogoType.BILL_HEADER, LogoType.BILL_FOOTER)
    override val previewCaption = "Bill Logo"
}

/**
 * "KOT Header Footer Logo" - the images printed at the head and foot of the kitchen
 * ticket.
 *
 * Its own slots, not the bill's. The two documents go to different people, and a shop
 * that wants its full logo on a customer's receipt often wants nothing but a plain
 * kitchen ticket - or a different mark on it entirely, telling one branch's pass from
 * another's.
 *
 * Restaurant mode only - a grocery till prints no kitchen ticket, so the tile and the
 * drawer entry are both hidden there.
 */
class KotLogoFragment : LogoFragment() {
    override val screenTitle = "KOT Header Footer Logo"
    override val myTypes = listOf(LogoType.KOT_HEADER, LogoType.KOT_FOOTER)
    override val previewCaption = "KOT Logo"
}

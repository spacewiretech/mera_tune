package com.spacewire.meratune.ui

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.spacewire.meratune.R

class UploadPhotoBottomSheet(
    private val context: Context,
    private val onCameraClick: () -> Unit,
    private val onGalleryClick: () -> Unit,
    private val onContinue: (Uri) -> Unit,
    private val onDismissed: () -> Unit,
) {
    private var dialog: BottomSheetDialog? = null
    private var previewContainer: View? = null
    private var previewView: ImageView? = null
    private var continueButton: TextView? = null
    private var selectedUri: Uri? = null
    private var completed = false

    fun show() {
        val sheetDialog = BottomSheetDialog(context)
        val sheetView = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_upload_photo, null)

        previewContainer = sheetView.findViewById(R.id.uploadPhotoPreviewContainer)
        previewView = sheetView.findViewById(R.id.uploadPhotoPreview)
        continueButton = sheetView.findViewById(R.id.uploadPhotoContinueButton)

        sheetView.findViewById<View>(R.id.uploadPhotoCameraRow).setOnClickListener { onCameraClick() }
        sheetView.findViewById<View>(R.id.uploadPhotoGalleryRow).setOnClickListener { onGalleryClick() }
        continueButton?.setOnClickListener {
            val uri = selectedUri ?: return@setOnClickListener
            completed = true
            onContinue(uri)
            sheetDialog.dismiss()
        }

        sheetDialog.setOnDismissListener {
            dialog = null
            if (!completed) {
                onDismissed()
            }
        }

        dialog = sheetDialog
        selectedUri?.let(::setPreview)
        sheetDialog.present(sheetView)
    }

    fun setPreview(uri: Uri) {
        selectedUri = uri
        previewContainer?.visibility = View.VISIBLE
        previewView?.load(uri) {
        crossfade(true)
    }
        continueButton?.isEnabled = true
        continueButton?.alpha = 1f
    }

    fun isShowing(): Boolean = dialog?.isShowing == true

    fun dismiss() {
        completed = true
        dialog?.dismiss()
        dialog = null
    }
}

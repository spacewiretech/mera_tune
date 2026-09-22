package com.spacewire.meratune.util

import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.spacewire.meratune.R

object AuthTermsHelper {

    private const val TERMS_URL = "http://meratune.app/terms/"
    private const val PRIVACY_URL = "http://meratune.app/privacy/"

    fun bind(textView: TextView) {
        val context = textView.context
        val termsLabel = context.getString(R.string.auth_terms_link)
        val privacyLabel = context.getString(R.string.auth_privacy_link)
        val fullText = context.getString(R.string.auth_terms, termsLabel, privacyLabel)
        val linkColor = ContextCompat.getColor(context, R.color.gradient_pink)

        val spannable = SpannableString(fullText)
        addLink(spannable, fullText, termsLabel, TERMS_URL, linkColor, textView)
        addLink(spannable, fullText, privacyLabel, PRIVACY_URL, linkColor, textView)

        textView.text = spannable
        textView.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun addLink(
        spannable: SpannableString,
        fullText: String,
        label: String,
        url: String,
        linkColor: Int,
        textView: TextView,
    ) {
        val start = fullText.indexOf(label)
        if (start < 0) return

        spannable.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    textView.context.startActivity(intent)
                }

                override fun updateDrawState(textPaint: TextPaint) {
                    super.updateDrawState(textPaint)
                    textPaint.color = linkColor
                    textPaint.isUnderlineText = true
                }
            },
            start,
            start + label.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
}

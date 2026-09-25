package com.spacewire.meratune.util

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import com.spacewire.meratune.R
import com.spacewire.meratune.analytics.ExternalLink
import com.spacewire.meratune.analytics.mixpanelAnalytics

object AuthTermsHelper {

    private const val TERMS_URL = "http://meratune.app/terms/"
    private const val PRIVACY_URL = "http://meratune.app/privacy/"

    /** [source] is the screen slug the footer is on (`external_link_opened.source`). */
    fun bind(textView: TextView, source: String) {
        val context = textView.context
        val termsLabel = context.getString(R.string.auth_terms_link)
        val privacyLabel = context.getString(R.string.auth_privacy_link)
        val fullText = context.getString(R.string.auth_terms, termsLabel, privacyLabel)

        val spannable = SpannableString(fullText)
        addLink(spannable, fullText, termsLabel, TERMS_URL, ExternalLink.TERMS, source, textView)
        addLink(spannable, fullText, privacyLabel, PRIVACY_URL, ExternalLink.PRIVACY_POLICY, source, textView)

        textView.text = spannable
        textView.movementMethod = LinkMovementMethod.getInstance()
        GradientTextHelper.bindSpans(textView)
    }

    private fun addLink(
        spannable: SpannableString,
        fullText: String,
        label: String,
        url: String,
        link: String,
        source: String,
        textView: TextView,
    ) {
        val start = fullText.indexOf(label)
        if (start < 0) return

        spannable.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val context = textView.context
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: ActivityNotFoundException) {
                        return
                    }
                    context.mixpanelAnalytics().trackExternalLinkOpened(link, source)
                }

                // Colour comes from the gradient span below; ClickableSpan's default would paint linkColor.
                override fun updateDrawState(textPaint: TextPaint) {
                    textPaint.isUnderlineText = true
                }
            },
            start,
            start + label.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        spannable.setSpan(
            GradientTextHelper.gradientSpan(
                textView.context,
                intArrayOf(R.color.gradient_pink, R.color.gradient_orange),
            ),
            start,
            start + label.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
}

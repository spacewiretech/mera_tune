package com.spacewire.meratune.ui

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import com.spacewire.meratune.R

class OtpInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val digitCount = DIGIT_COUNT
    private val fields = Array(digitCount) { index ->
        EditText(context).apply {
            // Wrap height with a 42dp floor, so large font scales grow the box instead of clipping.
            layoutParams = LayoutParams(dp(42), LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) marginStart = dp(10)
            }
            minimumHeight = dp(42)
            // Selector: gradient ring while focused or filled (isActivated), grey when empty.
            background = context.getDrawable(R.drawable.bg_otp_box)
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(1))
            imeOptions = if (index == digitCount - 1) {
                EditorInfo.IME_ACTION_DONE
            } else {
                EditorInfo.IME_ACTION_NEXT
            }
            textSize = 20f
            typeface = AppFonts.semibold(context)
            setTextColor(context.getColor(R.color.navy))
            if (index == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = IMPORTANT_FOR_AUTOFILL_YES
                setAutofillHints("smsOTPCode")
            }
        }
    }

    var onCompleteListener: ((String) -> Unit)? = null

    /** Called with the current digits after every change (fewer than [DIGIT_COUNT] while typing). */
    var onOtpChangedListener: ((String) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        fields.forEachIndexed { index, field ->
            addView(field)
            wireField(index, field)
        }
    }

    fun setOtp(otp: String) {
        val digits = otp.filter { it.isDigit() }.take(digitCount)
        fields.forEachIndexed { index, field ->
            field.setText(digits.getOrNull(index)?.toString().orEmpty())
        }
        if (digits.length == digitCount) {
            fields.last().clearFocus()
            onCompleteListener?.invoke(digits)
        } else if (digits.isNotEmpty()) {
            fields.getOrElse(digits.length) { fields.last() }.requestFocus()
        }
    }

    fun requestInitialFocus() {
        fields.first().requestFocus()
    }

    fun clear() {
        fields.forEach { it.setText("") }
        fields.first().requestFocus()
    }

    fun getOtp(): String = fields.joinToString("") { it.text.toString() }

    fun setEnabledState(enabled: Boolean) {
        fields.forEach { it.isEnabled = enabled }
    }

    private fun wireField(index: Int, field: EditText) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                field.isActivated = field.text.isNotEmpty()
                onOtpChangedListener?.invoke(getOtp())
                if ((s?.length ?: 0) == 1 && index < digitCount - 1) {
                    fields[index + 1].requestFocus()
                }
                if (getOtp().length == digitCount) {
                    onCompleteListener?.invoke(getOtp())
                }
            }
        })

        field.setOnKeyListener { _, keyCode, event ->
            if (
                keyCode == KeyEvent.KEYCODE_DEL &&
                event.action == KeyEvent.ACTION_DOWN &&
                field.text.isNullOrEmpty() &&
                index > 0
            ) {
                fields[index - 1].apply {
                    requestFocus()
                    setSelection(text?.length ?: 0)
                }
                true
            } else {
                false
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val DIGIT_COUNT = 4
    }
}

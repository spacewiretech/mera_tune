package com.spacewire.meratune.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.os.Parcel
import android.os.Parcelable
import android.transition.Fade
import android.transition.TransitionManager
import android.util.AttributeSet
import android.util.SparseArray
import android.view.AbsSavedState
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.spacewire.meratune.R
import kotlin.math.max

/**
 * The looping product-mockup carousel on the phone, OTP and name screens.
 *
 * - Auto-advances every [AUTO_ADVANCE_MS] only while the screen is RESUMED, the view is visible
 *   and not being dragged; never under TalkBack touch exploration or with animations turned off.
 *   Any page change (including a swipe) restarts the interval.
 * - The slide is shared across the three screens through the process-wide [sharedIndex], and
 *   survives process death through this view's own saved state (the pager's is not saved). A saved
 *   index is adopted only while [sharedIndexSet] is false, so a lower screen restored later (older
 *   index) does not rewind the slide the top screen restored.
 * - Purely decorative: not focusable and hidden from accessibility, like the designs' fake UI.
 * - [setImeVisible] hides it while the keyboard is open (invisible, keeping a fitted slot so the
 *   form stays right above the keyboard) and in landscape (gone).
 * - [fitHeightTo] sizes it to the space the rest of the screen leaves (see there).
 */
class OnboardingCarouselView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val pager: ViewPager2
    private val dots: List<View>
    private val slideCount = SLIDES.size
    private val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)

    private var lifecycleOwner: LifecycleOwner? = null
    private var dragging = false
    private var visibleToUser = false
    private var imeVisible = false

    private val minFittedHeight = resources.getDimensionPixelSize(R.dimen.onboarding_carousel_min_height)
    private val maxFittedHeight = resources.getDimensionPixelSize(R.dimen.onboarding_carousel_max_height)
    private var fitViewport: View? = null
    private var fitDirty = false
    private var consecutiveFitPasses = 0

    private val advance = Runnable {
        if (slideCount > 1) pager.setCurrentItem(pager.currentItem + 1, true)
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            // Back from OTP resumes the phone screen on the slide OTP reached.
            syncToSharedIndex()
            updateTicking()
        }

        override fun onPause(owner: LifecycleOwner) = updateTicking()
    }

    private val touchExplorationListener =
        AccessibilityManager.TouchExplorationStateChangeListener { updateTicking() }

    // Any layout of the viewport or the content (resize, IME, text re-wrap) may change the space
    // left; the pre-draw pass below recomputes it and changes nothing when it is the same.
    private val fitLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitDirty = true }

    /**
     * Applies the fitted height before the frame is drawn. When it changes the height it cancels
     * that draw, so the first frame already has the final size. The height only depends on the
     * rest of the content, so this settles after one extra pass; the counter is a safety net.
     */
    private val fitPreDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (!fitDirty) {
            consecutiveFitPasses = 0
            return@OnPreDrawListener true
        }
        fitDirty = false
        val changed = applyFittedHeight()
        if (changed && consecutiveFitPasses < MAX_CONSECUTIVE_FIT_PASSES) {
            consecutiveFitPasses++
            false
        } else {
            consecutiveFitPasses = 0
            true
        }
    }

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_onboarding_carousel, this, true)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS

        pager = findViewById(R.id.onboardingCarouselPager)
        pager.isSaveEnabled = false
        pager.offscreenPageLimit = 1
        (pager.getChildAt(0) as? RecyclerView)?.overScrollMode = OVER_SCROLL_NEVER
        pager.adapter = SlideAdapter()

        val dotsContainer = findViewById<LinearLayout>(R.id.onboardingCarouselDots)
        val dotSize = resources.getDimensionPixelSize(R.dimen.onboarding_carousel_dot_size)
        val dotGap = resources.getDimensionPixelSize(R.dimen.onboarding_carousel_dot_margin)
        dots = List(slideCount) {
            View(context).apply {
                layoutParams = LayoutParams(dotSize, dotSize).apply {
                    marginStart = dotGap
                    marginEnd = dotGap
                }
                setBackgroundResource(R.drawable.bg_carousel_dot)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                dotsContainer.addView(this)
            }
        }

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val real = CarouselPaging.realIndex(position, slideCount)
                // Only a real change counts as setting the index; the initial positioning
                // (the default slide) must not mark it set before a saved index is restored.
                if (real != sharedIndex) sharedIndex = real
                dots.forEachIndexed { index, dot -> dot.isSelected = index == real }
                updateTicking()
            }

            override fun onPageScrollStateChanged(state: Int) {
                when (state) {
                    ViewPager2.SCROLL_STATE_DRAGGING -> {
                        dragging = true
                        updateTicking()
                    }
                    ViewPager2.SCROLL_STATE_IDLE -> {
                        dragging = false
                        if (CarouselPaging.needsRecentre(pager.currentItem, slideCount)) {
                            pager.setCurrentItem(
                                CarouselPaging.startPosition(CarouselPaging.realIndex(pager.currentItem, slideCount), slideCount),
                                false,
                            )
                        }
                        updateTicking()
                    }
                    ViewPager2.SCROLL_STATE_SETTLING -> Unit
                }
            }
        })
        pager.setCurrentItem(CarouselPaging.startPosition(sharedIndex, slideCount), false)
        dots.forEachIndexed { index, dot -> dot.isSelected = index == sharedIndex }

        if (isLandscape()) isGone = true
    }

    /**
     * Hides the carousel while the keyboard is open, and always in landscape, so the form stays
     * on screen. Pass the IME state from every insets pass, including the first.
     *
     * With the keyboard open it turns INVISIBLE, not GONE: its slot keeps being fitted (down to 0),
     * so the content still fills the smaller viewport and the form stays anchored right above the
     * keyboard. GONE made the content shorter than the viewport, so the form jumped to the top of
     * the screen. Only the carousel fades: animating the siblings' bounds suppressed the scroll
     * view's layout mid-change and left a stale scroll offset (the form far above the keyboard).
     */
    fun setImeVisible(visible: Boolean) {
        imeVisible = visible
        val target = when {
            isLandscape() -> GONE
            visible -> INVISIBLE
            else -> VISIBLE
        }
        if (visibility == target) return
        val sceneRoot = parent as? ViewGroup
        if (sceneRoot != null && isLaidOut && ValueAnimator.areAnimatorsEnabled()) {
            TransitionManager.beginDelayedTransition(sceneRoot, Fade().setDuration(IME_TRANSITION_MS).addTarget(this))
        }
        visibility = target
        fitDirty = true
    }

    /**
     * Sizes the carousel from the space left in [viewport] (the scroll view whose content is this
     * view's parent): `available = viewport height - height of the rest of the content`, clamped to
     * `onboarding_carousel_min_height..onboarding_carousel_max_height` ([AuthImeLayout.carouselHeight];
     * the minimum is 0 while the keyboard is open and the carousel is invisible). On short screens
     * it shrinks so the form fits, down to the minimum, after which the page scrolls.
     *
     * The height is set explicitly (review #9): inside a scroll view a 0dp/weighted child would be
     * measured without a height limit and keep the slide's intrinsic size. It is recomputed when
     * the viewport or the content changes height, and applied only when it differs.
     */
    fun fitHeightTo(viewport: View) {
        if (fitViewport === viewport) return
        fitViewport?.removeOnLayoutChangeListener(fitLayoutListener)
        (parent as? View)?.removeOnLayoutChangeListener(fitLayoutListener)
        fitViewport = viewport
        viewport.addOnLayoutChangeListener(fitLayoutListener)
        (parent as? View)?.addOnLayoutChangeListener(fitLayoutListener)
        fitDirty = true
        if (isAttachedToWindow) {
            viewTreeObserver.removeOnPreDrawListener(fitPreDrawListener)
            viewTreeObserver.addOnPreDrawListener(fitPreDrawListener)
        }
    }

    /** Returns true when it changed the height (a new layout pass is then pending). */
    private fun applyFittedHeight(): Boolean {
        val viewport = fitViewport ?: return false
        val content = parent as? ViewGroup ?: return false
        if (isGone || !isLaidOut || !viewport.isLaidOut || !content.isLaidOut) return false

        // The content stacks top-down, so its natural height is the lowest child edge plus its
        // bottom padding, even when fillViewport stretches the content itself.
        var contentBottom = 0
        for (child in content.children) {
            if (child.isGone) continue
            val margin = (child.layoutParams as? MarginLayoutParams)?.bottomMargin ?: 0
            contentBottom = max(contentBottom, child.bottom + margin)
        }
        val naturalHeight = contentBottom + content.paddingBottom
        val rest = naturalHeight - height
        val viewportHeight = viewport.height - viewport.paddingTop - viewport.paddingBottom
        val target = AuthImeLayout.carouselHeight(viewportHeight, rest, minFittedHeight, maxFittedHeight, imeVisible)

        val params = layoutParams ?: return false
        if (params.height == target) return false
        params.height = target
        layoutParams = params
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycleOwner = findViewTreeLifecycleOwner()?.also { it.lifecycle.addObserver(lifecycleObserver) }
        accessibilityManager?.addTouchExplorationStateChangeListener(touchExplorationListener)
        if (fitViewport != null) {
            viewTreeObserver.removeOnPreDrawListener(fitPreDrawListener)
            viewTreeObserver.addOnPreDrawListener(fitPreDrawListener)
            fitDirty = true
        }
        syncToSharedIndex()
        updateTicking()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(advance)
        lifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
        lifecycleOwner = null
        accessibilityManager?.removeTouchExplorationStateChangeListener(touchExplorationListener)
        viewTreeObserver.removeOnPreDrawListener(fitPreDrawListener)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibleToUser = isVisible
        updateTicking()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        setImeVisible(imeVisible)
    }

    private fun syncToSharedIndex() {
        if (CarouselPaging.realIndex(pager.currentItem, slideCount) != sharedIndex) {
            pager.setCurrentItem(CarouselPaging.startPosition(sharedIndex, slideCount), false)
        }
    }

    private fun updateTicking() {
        removeCallbacks(advance)
        if (shouldTick()) postDelayed(advance, AUTO_ADVANCE_MS)
    }

    private fun shouldTick(): Boolean =
        slideCount > 1 &&
            isAttachedToWindow &&
            visibleToUser &&
            !dragging &&
            lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true &&
            accessibilityManager?.isTouchExplorationEnabled != true &&
            ValueAnimator.areAnimatorsEnabled()

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Only this view's own state (the slide index) is saved; the pager restores from sharedIndex.
    override fun dispatchSaveInstanceState(container: SparseArray<Parcelable>) = dispatchFreezeSelfOnly(container)

    override fun dispatchRestoreInstanceState(container: SparseArray<Parcelable>) = dispatchThawSelfOnly(container)

    override fun onSaveInstanceState(): Parcelable =
        SavedState(
            super.onSaveInstanceState() ?: AbsSavedState.EMPTY_STATE,
            CarouselPaging.realIndex(pager.currentItem, slideCount),
        )

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.superState)
        // After process death the top screen restores first and seeds the slide; a lower screen
        // restored later (back from OTP) holds an older index, so it follows the shared one.
        if (!sharedIndexSet) sharedIndex = CarouselPaging.realIndex(state.index, slideCount)
        syncToSharedIndex()
    }

    private class SavedState : BaseSavedState {
        val index: Int

        constructor(superState: Parcelable, index: Int) : super(superState) {
            this.index = index
        }

        private constructor(source: Parcel) : super(source) {
            index = source.readInt()
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeInt(index)
        }

        companion object {
            @JvmField
            val CREATOR = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(source: Parcel): SavedState = SavedState(source)

                override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
            }
        }
    }

    private class SlideHolder(val image: ImageView) : RecyclerView.ViewHolder(image)

    private class SlideAdapter : RecyclerView.Adapter<SlideHolder>() {
        override fun getItemCount(): Int = CarouselPaging.virtualCount(SLIDES.size)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideHolder =
            SlideHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_onboarding_slide, parent, false) as ImageView,
            )

        // Resources caches the decoded nodpi bitmaps, so rebinding is cheap and shares memory.
        override fun onBindViewHolder(holder: SlideHolder, position: Int) {
            holder.image.setImageResource(SLIDES[CarouselPaging.realIndex(position, SLIDES.size)])
        }
    }

    companion object {
        const val AUTO_ADVANCE_MS = 3_000L
        private const val IME_TRANSITION_MS = 150L
        private const val MAX_CONSECUTIVE_FIT_PASSES = 2

        /** Ready screen, Home list, Home empty search. */
        @DrawableRes
        internal val SLIDES = intArrayOf(
            R.drawable.onboarding_slide_1,
            R.drawable.onboarding_slide_2,
            R.drawable.onboarding_slide_3,
        )

        /** Process-wide current slide, so phone -> OTP -> name continue where the last screen was. */
        internal var sharedIndex = 0
            set(value) {
                field = value
                sharedIndexSet = true
            }

        /**
         * False until a carousel in this process changes or restores the slide. Only then does a
         * restored view adopt its saved index; afterwards [sharedIndex] is the newer truth.
         */
        internal var sharedIndexSet = false
            private set
    }
}

package com.spacewire.meratune.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.spacewire.meratune.R
import com.spacewire.meratune.data.RankedSong
import com.spacewire.meratune.data.Tune

/**
 * Song cards for the picker (`item_song_choice`). Selection and preview state live here so that
 * progress ticks and selection changes are delivered as payloads (no full rebinds, no flicker).
 */
class SongChoiceAdapter(
    private val onSongClick: (RankedSong) -> Unit,
) : RecyclerView.Adapter<SongChoiceAdapter.SongViewHolder>() {

    private var items: List<RankedSong> = emptyList()
    private var userName: String = ""

    var selectedId: String? = null
        private set

    private var previewId: String? = null
    private var previewPlaying: Boolean = false
    private var previewProgress: Float = 0f

    init {
        setHasStableIds(true)
    }

    fun submit(songs: List<RankedSong>, userName: String, selectedId: String?) {
        items = songs
        this.userName = userName
        this.selectedId = selectedId
        notifyDataSetChanged()
    }

    /** Moves the selection; the newly selected card plays the pop animation. */
    fun setSelected(id: String?) {
        val previous = selectedId
        if (previous == id) return
        selectedId = id
        indexOf(previous)?.let { notifyItemChanged(it, PAYLOAD_SELECTION) }
        indexOf(id)?.let { notifyItemChanged(it, PAYLOAD_SELECTION_POP) }
    }

    /** [id] is the card whose preview is loaded (playing or paused); `null` clears every ring. */
    fun setPreview(id: String?, isPlaying: Boolean) {
        val previous = previewId
        if (previous != id) previewProgress = 0f
        previewId = id
        previewPlaying = id != null && isPlaying
        indexOf(previous)?.let { notifyItemChanged(it, PAYLOAD_PLAYBACK) }
        if (id != previous) indexOf(id)?.let { notifyItemChanged(it, PAYLOAD_PLAYBACK) }
    }

    fun updateProgress(id: String, progress: Float) {
        if (id != previewId) return
        val next = progress.coerceIn(0f, 1f)
        if (next == previewProgress) return
        previewProgress = next
        indexOf(id)?.let { notifyItemChanged(it, PAYLOAD_PROGRESS) }
    }

    fun songAt(id: String): RankedSong? = items.firstOrNull { it.tune.id == id }

    private fun indexOf(id: String?): Int? {
        if (id == null) return null
        return items.indexOfFirst { it.tune.id == id }.takeIf { it >= 0 }
    }

    override fun getItemId(position: Int): Long = items[position].tune.id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song_choice, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val song = items[position]
        if (PAYLOAD_SELECTION in payloads || PAYLOAD_SELECTION_POP in payloads) {
            holder.bindSelection(song, animatePop = PAYLOAD_SELECTION_POP in payloads)
        }
        if (PAYLOAD_PLAYBACK in payloads) {
            holder.bindPlayback(song.tune)
        } else if (PAYLOAD_PROGRESS in payloads) {
            holder.bindProgress(song.tune)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: View = itemView.findViewById(R.id.songCard)
        private val playbackRing: PlaybackRingView = itemView.findViewById(R.id.playbackRing)
        private val thumbnailContainer: View = itemView.findViewById(R.id.thumbnailContainer)
        private val thumbnailIcon: ImageView = itemView.findViewById(R.id.thumbnailIcon)
        private val playButton: ImageButton = itemView.findViewById(R.id.playButton)
        private val title: TextView = itemView.findViewById(R.id.songTitle)
        private val voiceBadge: TextView = itemView.findViewById(R.id.voiceBadge)
        private val sampleText: TextView = itemView.findViewById(R.id.sampleText)
        private val replaceHint: TextView = itemView.findViewById(R.id.replaceHint)
        private val radioIndicator: View = itemView.findViewById(R.id.radioIndicator)
        private val radioCheck: ImageView = itemView.findViewById(R.id.radioCheck)

        init {
            card.setOnTouchListener(PressScaleTouchListener)
        }

        fun bind(song: RankedSong) {
            val tune = song.tune
            val context = itemView.context
            title.text = tune.name

            val category = tune.category
            if (category != null) {
                CategoryUiHelper.bindIcon(thumbnailContainer, thumbnailIcon, category)
            } else {
                val fallback = CategoryUiHelper.styleFor("")
                thumbnailContainer.setBackgroundResource(fallback.circleBg)
                thumbnailIcon.setImageResource(fallback.iconRes)
            }

            when (tune.voiceKey) {
                Tune.VOICE_MALE -> {
                    voiceBadge.setText(R.string.create_form_voice_male)
                    voiceBadge.visibility = View.VISIBLE
                }

                Tune.VOICE_FEMALE -> {
                    voiceBadge.setText(R.string.create_form_voice_female)
                    voiceBadge.visibility = View.VISIBLE
                }

                else -> voiceBadge.visibility = View.GONE
            }

            val sampleName = tune.sampleName?.trim().orEmpty()
            if (sampleName.isNotEmpty()) {
                sampleText.text = context.getString(R.string.song_choice_sample, sampleName)
                sampleText.visibility = View.VISIBLE
            } else {
                sampleText.visibility = View.GONE
            }

            card.setOnClickListener { onSongClick(song) }
            playButton.setOnClickListener { onSongClick(song) }

            card.animate().cancel()
            card.scaleX = 1f
            card.scaleY = 1f
            bindSelection(song, animatePop = false)
            bindPlayback(tune)
        }

        fun bindSelection(song: RankedSong, animatePop: Boolean) {
            val tune = song.tune
            val context = itemView.context
            val isSelected = tune.id == selectedId

            card.setBackgroundResource(if (isSelected) R.drawable.bg_video_frame else R.drawable.bg_photo_source_card)
            radioIndicator.setBackgroundResource(
                if (isSelected) R.drawable.bg_form_option_selected else R.drawable.bg_form_option_unselected,
            )
            radioCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

            val sampleName = tune.sampleName?.trim().orEmpty()
            if (isSelected && sampleName.isNotEmpty() && userName.isNotBlank()) {
                replaceHint.text = context.getString(R.string.song_choice_replaces, sampleName, userName)
                replaceHint.visibility = View.VISIBLE
            } else {
                replaceHint.visibility = View.GONE
            }

            ViewCompat.setStateDescription(
                card,
                if (isSelected) context.getString(R.string.song_choice_selected_a11y, tune.name) else null,
            )

            if (isSelected && animatePop) playSelectionPop()
        }

        fun bindPlayback(tune: Tune) {
            val isCurrent = tune.id == previewId
            val isPlaying = isCurrent && previewPlaying
            playButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            playbackRing.visibility = if (isCurrent) View.VISIBLE else View.GONE
            playbackRing.progress = if (isCurrent) previewProgress else 0f
        }

        fun bindProgress(tune: Tune) {
            if (tune.id != previewId) return
            playbackRing.visibility = View.VISIBLE
            playbackRing.progress = previewProgress
        }

        private fun playSelectionPop() {
            card.animate().cancel()
            card.animate()
                .scaleX(POP_SCALE)
                .scaleY(POP_SCALE)
                .setDuration(POP_UP_MS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    card.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(POP_DOWN_MS)
                        .setInterpolator(OvershootInterpolator())
                        .start()
                }
                .start()
        }
    }

    /** Press feedback: shrink to 0.98 while pressed. Returns false so click and ripple still run. */
    private object PressScaleTouchListener : View.OnTouchListener {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE).setDuration(PRESS_MS).start()

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> view.animate().scaleX(1f).scaleY(1f).setDuration(PRESS_MS).start()
            }
            return false
        }
    }

    private companion object {
        const val PAYLOAD_PROGRESS = "progress"
        const val PAYLOAD_PLAYBACK = "playback"
        const val PAYLOAD_SELECTION = "selection"
        const val PAYLOAD_SELECTION_POP = "selection_pop"
        const val PRESS_SCALE = 0.98f
        const val POP_SCALE = 1.04f
        const val PRESS_MS = 90L
        const val POP_UP_MS = 120L
        const val POP_DOWN_MS = 180L
    }
}

package com.spacewire.meratune.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.spacewire.meratune.R
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.util.FormatUtils

/**
 * Rows of the existing name ringtones step (`item_name_ringtone`), keyed by tune id. The art
 * toggles the preview ([onPlayClick]); the Set pill starts the set flow ([onSetClick]). Both get the
 * row's 1-based rank. Preview, progress and Set state arrive as payloads, so they never rebind the art.
 */
class NameRingtoneAdapter(
    private val onPlayClick: (Tune, Int) -> Unit,
    private val onSetClick: (Tune, Int) -> Unit,
) : RecyclerView.Adapter<NameRingtoneAdapter.RowViewHolder>() {

    private var items: List<Tune> = emptyList()
    private var previewId: String? = null
    private var previewPlaying = false
    private var previewProgress = 0f
    private var claimingId: String? = null
    private var setId: String? = null

    init {
        setHasStableIds(true)
    }

    fun submit(rows: List<Tune>) {
        items = rows
        notifyDataSetChanged()
    }

    /** [id] is the row whose preview is loaded (playing or paused); `null` shows play on every row. */
    fun setPreview(id: String?, isPlaying: Boolean) {
        val previous = previewId
        previewId = id
        previewPlaying = id != null && isPlaying
        if (id != previous) previewProgress = 0f
        notifyRow(previous, PAYLOAD_PLAYBACK)
        if (id != previous) notifyRow(id, PAYLOAD_PLAYBACK)
    }

    fun updateProgress(progress: Float) {
        val next = progress.coerceIn(0f, 1f)
        if (next == previewProgress) return
        previewProgress = next
        notifyRow(previewId, PAYLOAD_PROGRESS)
    }

    /** Spinner on [id]'s Set pill while it is recorded for the user; `null` clears it. */
    fun setClaiming(id: String?) {
        val previous = claimingId
        claimingId = id
        notifyRow(previous, PAYLOAD_SET)
        notifyRow(id, PAYLOAD_SET)
    }

    /** [id]'s pill turns into the disabled "Active" pill after a successful set. */
    fun setActive(id: String?) {
        val previous = setId
        setId = id
        notifyRow(previous, PAYLOAD_SET)
        notifyRow(id, PAYLOAD_SET)
    }

    private fun notifyRow(id: String?, payload: String) {
        if (id == null) return
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) notifyItemChanged(index, payload)
    }

    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_name_ringtone, parent, false)
        return RowViewHolder(view)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.bind(items[position], position + 1)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val tune = items[position]
        if (PAYLOAD_PLAYBACK in payloads) holder.bindPlayback(tune)
        if (PAYLOAD_PROGRESS in payloads) holder.bindProgress(tune)
        if (PAYLOAD_SET in payloads) holder.bindSet(tune, position + 1)
    }

    inner class RowViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnailContainer: View = itemView.findViewById(R.id.thumbnailContainer)
        private val thumbnailIcon: ImageView = itemView.findViewById(R.id.thumbnailIcon)
        private val playButton: ImageButton = itemView.findViewById(R.id.playButton)
        private val playbackRing: PlaybackRingView = itemView.findViewById(R.id.playbackRing)
        private val title: TextView = itemView.findViewById(R.id.ringtoneTitle)
        private val voiceBadge: TextView = itemView.findViewById(R.id.voiceBadge)
        private val viewsCount: TextView = itemView.findViewById(R.id.viewsCount)
        private val setButton: TextView = itemView.findViewById(R.id.setButton)
        private val setProgress: View = itemView.findViewById(R.id.setProgress)

        fun bind(tune: Tune, rank: Int) {
            title.text = tune.name
            viewsCount.text = FormatUtils.formatCount(tune.viewsCount)
            val voiceRes = NameRingtonesPolicy.voiceLabelRes(tune.voiceKey)
            if (voiceRes != null) voiceBadge.setText(voiceRes)
            voiceBadge.visibility = if (voiceRes != null) View.VISIBLE else View.GONE
            CategoryUiHelper.bindArt(thumbnailContainer, thumbnailIcon, tune.category)

            playButton.setOnClickListener { onPlayClick(tune, rank) }
            bindPlayback(tune)
            bindSet(tune, rank)
        }

        fun bindPlayback(tune: Tune) {
            val isPlaying = tune.id == previewId && previewPlaying
            playButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            playButton.contentDescription = itemView.context.getString(
                if (isPlaying) R.string.home_pause_tune else R.string.play_tune,
            )
            bindProgress(tune)
        }

        /** The ring shows on the loaded preview (playing or paused). */
        fun bindProgress(tune: Tune) {
            val isLoaded = tune.id == previewId
            playbackRing.visibility = if (isLoaded) View.VISIBLE else View.GONE
            playbackRing.progress = if (isLoaded) previewProgress else 0f
        }

        fun bindSet(tune: Tune, rank: Int) {
            val context = itemView.context
            val claiming = tune.id == claimingId
            setProgress.visibility = if (claiming) View.VISIBLE else View.GONE
            if (tune.id == setId) {
                setButton.text = context.getString(R.string.active_ringtone)
                setButton.setTextColor(ContextCompat.getColor(context, R.color.white))
                setButton.setBackgroundResource(R.drawable.bg_set_button_active)
                setButton.isEnabled = false
                setButton.setOnClickListener(null)
                setButton.contentDescription = null
                return
            }
            setButton.text = context.getString(R.string.set)
            // Transparent while the spinner covers the pill, so it keeps its width.
            setButton.setTextColor(if (claiming) Color.TRANSPARENT else ContextCompat.getColor(context, R.color.navy))
            setButton.setBackgroundResource(R.drawable.bg_row_pill)
            setButton.isEnabled = !claiming
            setButton.contentDescription = if (claiming) {
                context.getString(R.string.cta_loading)
            } else {
                context.getString(R.string.name_ringtones_set_a11y, tune.name)
            }
            setButton.setOnClickListener { onSetClick(tune, rank) }
        }
    }

    private companion object {
        const val PAYLOAD_PLAYBACK = "playback"
        const val PAYLOAD_PROGRESS = "progress"
        const val PAYLOAD_SET = "set"
    }
}

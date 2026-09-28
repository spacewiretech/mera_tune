package com.spacewire.meratune.ui

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

class TuneAdapter(
    private val onPlayClick: (Tune) -> Unit,
    private val onSetClick: (Tune) -> Unit,
) : RecyclerView.Adapter<TuneAdapter.TuneViewHolder>() {

    private var items: List<Tune> = emptyList()

    /** Row keys ([Tune.rowKey]): an own ringtone shares its base tune id with the catalog tune. */
    private var playingTuneId: String? = null
    private var activeRingtoneId: String? = null
    private var playbackProgress: Float = 0f

    fun submitList(tunes: List<Tune>, playingId: String?, activeId: String?) {
        items = tunes
        playingTuneId = playingId
        activeRingtoneId = activeId
        if (playingId == null) {
            playbackProgress = 0f
        }
        notifyDataSetChanged()
    }

    fun updatePlaybackProgress(progress: Float) {
        val nextProgress = progress.coerceIn(0f, 1f)
        if (playbackProgress == nextProgress) return

        playbackProgress = nextProgress
        val playingId = playingTuneId ?: return
        val index = items.indexOfFirst { it.rowKey == playingId }
        if (index >= 0) {
            notifyItemChanged(index, PAYLOAD_PROGRESS)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TuneViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tune, parent, false)
        return TuneViewHolder(view)
    }

    override fun onBindViewHolder(holder: TuneViewHolder, position: Int) {
        holder.bind(items[position], playbackProgress)
    }

    override fun onBindViewHolder(holder: TuneViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PROGRESS)) {
            holder.updateProgress(playingTuneId == items[position].rowKey, playbackProgress)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class TuneViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val playbackRing: PlaybackRingView = itemView.findViewById(R.id.playbackRing)
        private val thumbnailContainer: View = itemView.findViewById(R.id.thumbnailContainer)
        private val thumbnailIcon: ImageView = itemView.findViewById(R.id.thumbnailIcon)
        private val playButton: ImageButton = itemView.findViewById(R.id.playButton)
        private val title: TextView = itemView.findViewById(R.id.tuneTitle)
        private val likesCount: TextView = itemView.findViewById(R.id.likesCount)
        private val viewsCount: TextView = itemView.findViewById(R.id.viewsCount)
        private val setButton: TextView = itemView.findViewById(R.id.setButton)

        fun bind(tune: Tune, progress: Float) {
            title.text = tune.name
            likesCount.text = FormatUtils.formatCount(tune.likesCount)
            viewsCount.text = FormatUtils.formatCount(tune.viewsCount)

            CategoryUiHelper.bindArt(thumbnailContainer, thumbnailIcon, tune.category)

            val isPlaying = playingTuneId == tune.rowKey
            playButton.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            )
            playButton.contentDescription = itemView.context.getString(
                if (isPlaying) R.string.home_pause_tune else R.string.play_tune,
            )
            updateProgress(isPlaying, if (isPlaying) progress else 0f)
            bindSetButton(tune)

            playButton.setOnClickListener { onPlayClick(tune) }
        }

        private fun bindSetButton(tune: Tune) {
            val isActive = activeRingtoneId == tune.rowKey
            if (isActive) {
                setButton.text = itemView.context.getString(R.string.active_ringtone)
                setButton.setTextColor(ContextCompat.getColor(itemView.context, R.color.white))
                setButton.setBackgroundResource(R.drawable.bg_set_button_active)
                setButton.isEnabled = false
                setButton.setOnClickListener(null)
            } else {
                setButton.text = itemView.context.getString(R.string.set)
                setButton.setTextColor(ContextCompat.getColor(itemView.context, R.color.navy))
                setButton.setBackgroundResource(R.drawable.bg_row_pill)
                setButton.isEnabled = true
                setButton.setOnClickListener { onSetClick(tune) }
            }
        }

        fun updateProgress(isPlaying: Boolean, progress: Float) {
            playbackRing.visibility = if (isPlaying) View.VISIBLE else View.GONE
            playbackRing.progress = progress
        }
    }

    private companion object {
        const val PAYLOAD_PROGRESS = "progress"
    }
}

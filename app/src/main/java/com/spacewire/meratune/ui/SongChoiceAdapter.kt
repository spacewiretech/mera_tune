package com.spacewire.meratune.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.spacewire.meratune.R
import com.spacewire.meratune.data.RankedSong
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.util.FormatUtils

/**
 * Flat song rows for the picker (`item_song_choice`). A row or art tap previews ([onPreviewClick]);
 * the "chuno" pill commits ([onChooseClick]). Preview state is delivered as a payload, so a
 * play/pause change never rebinds the art.
 */
class SongChoiceAdapter(
    private val onPreviewClick: (RankedSong) -> Unit,
    private val onChooseClick: (RankedSong) -> Unit,
) : RecyclerView.Adapter<SongChoiceAdapter.SongViewHolder>() {

    private var items: List<RankedSong> = emptyList()
    private var previewId: String? = null
    private var previewPlaying: Boolean = false

    init {
        setHasStableIds(true)
    }

    fun submit(songs: List<RankedSong>) {
        items = songs
        notifyDataSetChanged()
    }

    /** [id] is the row whose preview is loaded (playing or paused); `null` shows play on every row. */
    fun setPreview(id: String?, isPlaying: Boolean) {
        val previous = previewId
        previewId = id
        previewPlaying = id != null && isPlaying
        indexOf(previous)?.let { notifyItemChanged(it, PAYLOAD_PLAYBACK) }
        if (id != previous) indexOf(id)?.let { notifyItemChanged(it, PAYLOAD_PLAYBACK) }
    }

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
        if (PAYLOAD_PLAYBACK in payloads) holder.bindPlayback(items[position].tune)
    }

    override fun getItemCount(): Int = items.size

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnailContainer: View = itemView.findViewById(R.id.thumbnailContainer)
        private val thumbnailIcon: ImageView = itemView.findViewById(R.id.thumbnailIcon)
        private val playButton: ImageButton = itemView.findViewById(R.id.playButton)
        private val title: TextView = itemView.findViewById(R.id.songTitle)
        private val likesCount: TextView = itemView.findViewById(R.id.likesCount)
        private val viewsCount: TextView = itemView.findViewById(R.id.viewsCount)
        private val chooseButton: TextView = itemView.findViewById(R.id.chooseButton)

        fun bind(song: RankedSong) {
            val tune = song.tune
            val context = itemView.context
            title.text = tune.name
            likesCount.text = FormatUtils.formatCount(tune.likesCount)
            viewsCount.text = FormatUtils.formatCount(tune.viewsCount)
            CategoryUiHelper.bindArt(thumbnailContainer, thumbnailIcon, tune.category)

            itemView.contentDescription = tune.name
            chooseButton.contentDescription = context.getString(R.string.song_choice_choose_a11y, tune.name)

            itemView.setOnClickListener { onPreviewClick(song) }
            playButton.setOnClickListener { onPreviewClick(song) }
            chooseButton.setOnClickListener { onChooseClick(song) }
            bindPlayback(tune)
        }

        fun bindPlayback(tune: Tune) {
            val isPlaying = tune.id == previewId && previewPlaying
            playButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }
    }

    private companion object {
        const val PAYLOAD_PLAYBACK = "playback"
    }
}

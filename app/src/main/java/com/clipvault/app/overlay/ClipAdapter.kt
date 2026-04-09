package com.clipvault.app.overlay

import android.graphics.Color
import android.graphics.Typeface
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.clipvault.app.R
import com.clipvault.app.data.ClipEntity

class ClipAdapter(
    private val onClipTap: (ClipEntity) -> Unit,
    private val onPinToggle: (ClipEntity) -> Unit,
    private val onDelete: (ClipEntity) -> Unit,
    private val onSelectionChanged: (Set<Long>) -> Unit
) : ListAdapter<ClipEntity, ClipAdapter.ClipViewHolder>(ClipDiffCallback()) {

    private val selectedIds = mutableSetOf<Long>()
    val isInSelectionMode: Boolean get() = selectedIds.isNotEmpty()

    fun getSelectedIds(): Set<Long> = selectedIds.toSet()

    fun clearSelection() {
        selectedIds.clear()
        onSelectionChanged(selectedIds)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClipViewHolder {
        val context = parent.context
        val dp = context.resources.displayMetrics.density

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.clip_item_bg)
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (6 * dp).toInt()
            }
        }

        val previewText = TextView(context).apply {
            id = View.generateViewId()
            setTextColor(Color.parseColor("#EEEEF2"))
            textSize = 14f
            maxLines = 3
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        card.addView(previewText)

        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6 * dp).toInt()
            }
        }

        val timeText = TextView(context).apply {
            id = View.generateViewId()
            setTextColor(Color.parseColor("#88FFFFFF"))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bottomRow.addView(timeText)

        val pinIcon = TextView(context).apply {
            id = View.generateViewId()
            textSize = 18f
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
        }
        bottomRow.addView(pinIcon)

        val deleteIcon = TextView(context).apply {
            id = View.generateViewId()
            text = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#FF6B6B"))
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
        }
        bottomRow.addView(deleteIcon)

        card.addView(bottomRow)

        return ClipViewHolder(card, previewText, timeText, pinIcon, deleteIcon)
    }

    override fun onBindViewHolder(holder: ClipViewHolder, position: Int) {
        val clip = getItem(position)
        val isSelected = selectedIds.contains(clip.id)
        holder.bind(
            clip = clip,
            isSelected = isSelected,
            isSelectionMode = isInSelectionMode,
            onTap = { c ->
                if (isInSelectionMode) {
                    toggleSelection(c.id)
                    notifyItemChanged(position)
                } else {
                    onClipTap(c)
                }
            },
            onLongPress = { c ->
                if (!isInSelectionMode) {
                    toggleSelection(c.id)
                    notifyItemChanged(position)
                }
            },
            onPinToggle = onPinToggle,
            onDelete = onDelete
        )
    }

    private fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) {
            selectedIds.remove(id)
        } else {
            selectedIds.add(id)
        }
        onSelectionChanged(selectedIds)
    }

    class ClipViewHolder(
        itemView: View,
        private val previewText: TextView,
        private val timeText: TextView,
        private val pinIcon: TextView,
        private val deleteIcon: TextView
    ) : RecyclerView.ViewHolder(itemView) {

        fun bind(
            clip: ClipEntity,
            isSelected: Boolean,
            isSelectionMode: Boolean,
            onTap: (ClipEntity) -> Unit,
            onLongPress: (ClipEntity) -> Unit,
            onPinToggle: (ClipEntity) -> Unit,
            onDelete: (ClipEntity) -> Unit
        ) {
            // Selection indicator + preview
            val prefix = if (isSelectionMode) {
                if (isSelected) "☑ " else "☐ "
            } else ""
            previewText.text = prefix + clip.preview
            previewText.setTypeface(null, if (clip.isPinned) Typeface.BOLD else Typeface.NORMAL)

            timeText.text = DateUtils.getRelativeTimeSpanString(
                clip.createdAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )

            pinIcon.text = if (clip.isPinned) "📌" else "📍"

            // Selection highlight
            if (isSelected) {
                (itemView as? LinearLayout)?.setBackgroundColor(Color.parseColor("#44BB86FC"))
            } else {
                (itemView as? LinearLayout)?.setBackgroundResource(R.drawable.clip_item_bg)
            }

            itemView.setOnClickListener { onTap(clip) }
            itemView.setOnLongClickListener { onLongPress(clip); true }

            // Hide individual action buttons during multi-select
            pinIcon.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
            deleteIcon.visibility = if (isSelectionMode) View.GONE else View.VISIBLE

            if (!isSelectionMode) {
                pinIcon.setOnClickListener { onPinToggle(clip) }
                deleteIcon.setOnClickListener { onDelete(clip) }
            }
        }
    }

    class ClipDiffCallback : DiffUtil.ItemCallback<ClipEntity>() {
        override fun areItemsTheSame(oldItem: ClipEntity, newItem: ClipEntity): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ClipEntity, newItem: ClipEntity): Boolean =
            oldItem == newItem
    }
}

package com.clipvault.app.overlay

import android.app.AlertDialog
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clipvault.app.R
import com.clipvault.app.data.ClipRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class OverlayPanelManager(
    private val context: Context,
    private val repository: ClipRepository,
    private val scope: CoroutineScope,
    private val onClipSelected: (String) -> Unit
) {
    private var overlayView: View? = null
    private var windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var collectJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private var pinnedFirst: Boolean = true
    private var adapter: ClipAdapter? = null

    var isShowing: Boolean = false
        private set

    var showTimestamp: Long = 0L
        private set

    fun show() {
        if (isShowing) return

        // Reset to default sort each time overlay opens
        pinnedFirst = true
        showTimestamp = System.currentTimeMillis()

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.overlay_clipboard_panel, null)
        overlayView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.END or Gravity.FILL_VERTICAL

        windowManager.addView(view, params)
        isShowing = true

        setupPanel(view)
    }

    fun dismiss() {
        if (!isShowing) return
        collectJob?.cancel()
        collectJob = null
        searchRunnable?.let { handler.removeCallbacks(it) }
        adapter = null
        try {
            overlayView?.let { windowManager.removeView(it) }
        } catch (_: Exception) { }
        overlayView = null
        isShowing = false
    }

    private fun setupPanel(view: View) {
        val dismissArea = view.findViewById<View>(R.id.dismiss_area)
        val searchEditText = view.findViewById<EditText>(R.id.search_edit_text)
        val recyclerView = view.findViewById<RecyclerView>(R.id.clips_recycler_view)
        val sortToggleBtn = view.findViewById<TextView>(R.id.sort_toggle_btn)
        val multiSelectBar = view.findViewById<LinearLayout>(R.id.multi_select_bar)
        val selectionCountText = view.findViewById<TextView>(R.id.selection_count_text)
        val actionPinBtn = view.findViewById<TextView>(R.id.action_pin_btn)
        val actionUnpinBtn = view.findViewById<TextView>(R.id.action_unpin_btn)
        val actionDeleteBtn = view.findViewById<TextView>(R.id.action_delete_btn)
        val actionCancelBtn = view.findViewById<TextView>(R.id.action_cancel_btn)

        val clipAdapter = ClipAdapter(
            onClipTap = { clip ->
                scope.launch { repository.updateLastUsed(clip.id) }
                onClipSelected(clip.text)
            },
            onPinToggle = { clip ->
                scope.launch { repository.togglePin(clip.id) }
            },
            onDelete = { clip ->
                showConfirmDialog("Delete this clip?", clip.preview.take(50) + "…") {
                    scope.launch { repository.delete(clip.id) }
                }
            },
            onSelectionChanged = { selectedIds ->
                if (selectedIds.isEmpty()) {
                    multiSelectBar.visibility = View.GONE
                } else {
                    multiSelectBar.visibility = View.VISIBLE
                    selectionCountText.text = "${selectedIds.size} selected"
                }
            }
        )
        adapter = clipAdapter

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = clipAdapter

        // Sort toggle
        updateSortLabel(sortToggleBtn)
        sortToggleBtn.setOnClickListener {
            pinnedFirst = !pinnedFirst
            updateSortLabel(sortToggleBtn)
            val query = searchEditText.text?.toString()?.trim()
            observeClips(clipAdapter, if (query.isNullOrEmpty()) null else query)
        }

        // Add clip button
        val addClipBtn = view.findViewById<TextView>(R.id.add_clip_btn)
        addClipBtn.setOnClickListener { showAddClipDialog() }

        // Multi-select action buttons
        actionPinBtn.setOnClickListener {
            val ids = clipAdapter.getSelectedIds().toList()
            if (ids.isNotEmpty()) {
                scope.launch { repository.pinMany(ids) }
                clipAdapter.clearSelection()
            }
        }
        actionUnpinBtn.setOnClickListener {
            val ids = clipAdapter.getSelectedIds().toList()
            if (ids.isNotEmpty()) {
                scope.launch { repository.unpinMany(ids) }
                clipAdapter.clearSelection()
            }
        }
        actionDeleteBtn.setOnClickListener {
            val ids = clipAdapter.getSelectedIds().toList()
            if (ids.isNotEmpty()) {
                showConfirmDialog("Delete ${ids.size} clips?", "This cannot be undone.") {
                    scope.launch { repository.deleteMany(ids) }
                    clipAdapter.clearSelection()
                }
            }
        }
        actionCancelBtn.setOnClickListener {
            clipAdapter.clearSelection()
        }

        // Load all clips with default sort
        observeClips(clipAdapter, null)

        // Search with debounce
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchRunnable?.let { handler.removeCallbacks(it) }
                searchRunnable = Runnable {
                    val query = s?.toString()?.trim()
                    observeClips(clipAdapter, if (query.isNullOrEmpty()) null else query)
                }
                handler.postDelayed(searchRunnable!!, 300)
            }
        })

        dismissArea.setOnClickListener { dismiss() }
    }

    private fun updateSortLabel(btn: TextView) {
        btn.text = if (pinnedFirst) "📌 first" else "⏱ recent"
    }

    private fun showAddClipDialog() {
        val editText = EditText(context).apply {
            hint = "Enter text to save…"
            minLines = 3
            setPadding(40, 20, 40, 20)
        }
        // Use TYPE_ACCESSIBILITY_OVERLAY so the dialog shows above everything
        val dialog = AlertDialog.Builder(context)
            .setTitle("Add clip")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val text = editText.text?.toString()
                if (!text.isNullOrBlank()) {
                    scope.launch { repository.addClip(text) }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog.show()
    }

    private fun showConfirmDialog(title: String, message: String, onConfirm: () -> Unit) {
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog.show()
    }

    private fun observeClips(adapter: ClipAdapter, query: String?) {
        collectJob?.cancel()
        collectJob = scope.launch {
            val flow = if (query != null) {
                repository.searchClips(query, pinnedFirst)
            } else {
                repository.getAllClips(pinnedFirst)
            }
            flow.collectLatest { clips ->
                adapter.submitList(clips)
            }
        }
    }
}

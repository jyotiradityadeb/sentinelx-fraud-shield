package com.sentinelx.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sentinelx.R
import com.sentinelx.core.AddedReason
import com.sentinelx.core.BlocklistEntry
import com.sentinelx.core.BlocklistManager
import com.sentinelx.databinding.ActivityBlocklistBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BlocklistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlocklistBinding
    private lateinit var manager: BlocklistManager
    private lateinit var adapter: BlocklistAdapter
    private var allEntries: List<BlocklistEntry> = emptyList()

    private val blocklistChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BlocklistManager.ACTION_BLOCKLIST_CHANGED) refreshList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlocklistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        manager = BlocklistManager(this)
        adapter = BlocklistAdapter(emptyList()) { entry -> confirmRemove(entry) }

        binding.rvBlocklist.layoutManager = LinearLayoutManager(this)
        binding.rvBlocklist.adapter = adapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val entry = adapter.getItem(vh.adapterPosition)
                manager.unblockNumber(entry.normalizedNumber)
                Toast.makeText(this@BlocklistActivity, "${entry.displayNumber} removed", Toast.LENGTH_SHORT).show()
                refreshList()
            }
        }).attachToRecyclerView(binding.rvBlocklist)

        binding.btnBack.setOnClickListener { finish() }
        binding.fabAddNumber.setOnClickListener { showAddDialog() }
        binding.btnImportCsv.setOnClickListener { showImportDialog() }
        binding.btnExportCsv.setOnClickListener { exportCsv() }
        binding.btnClearAll.setOnClickListener { confirmClearAll() }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        refreshList()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(BlocklistManager.ACTION_BLOCKLIST_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(blocklistChangedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(blocklistChangedReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(blocklistChangedReceiver) }
    }

    private fun refreshList() {
        allEntries = manager.getBlockedEntries().sortedByDescending { it.addedTimestamp }
        filterList(binding.etSearch.text?.toString().orEmpty())
        val count = allEntries.size
        binding.tvBlocklistSubtitle.text = "$count number${if (count == 1) "" else "s"} blocked"
    }

    private fun filterList(query: String) {
        val filtered = if (query.isBlank()) allEntries
        else allEntries.filter { it.displayNumber.contains(query, ignoreCase = true) || it.normalizedNumber.contains(query) }
        adapter.updateList(filtered)
        binding.emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.rvBlocklist.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showAddDialog() {
        val numberInput = EditText(this).apply {
            hint = "Phone number"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val reasonSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@BlocklistActivity,
                android.R.layout.simple_spinner_item,
                AddedReason.values().map { it.name },
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
            addView(numberInput)
            addView(reasonSpinner)
        }
        AlertDialog.Builder(this)
            .setTitle("Block Number")
            .setView(container)
            .setPositiveButton("Block") { _, _ ->
                val number = numberInput.text.toString().trim()
                val reason = AddedReason.values()[reasonSpinner.selectedItemPosition]
                if (number.isBlank()) {
                    Toast.makeText(this, "Enter a phone number", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val added = manager.blockNumber(number, reason)
                Toast.makeText(this, if (added) "$number blocked" else "Already in blocklist", Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = "Paste numbers (comma or line separated)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
        }
        AlertDialog.Builder(this)
            .setTitle("Import Numbers")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val result = manager.importFromCsv(input.text.toString())
                Toast.makeText(
                    this,
                    "Added: ${result.added}  Duplicates: ${result.duplicates}  Skipped: ${result.skipped}",
                    Toast.LENGTH_LONG,
                ).show()
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportCsv() {
        val csv = manager.exportAsCsv()
        if (csv.isBlank()) {
            Toast.makeText(this, "Nothing to export", Toast.LENGTH_SHORT).show()
            return
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, csv)
            putExtra(Intent.EXTRA_SUBJECT, "SentinelX Blocklist Export")
        }
        startActivity(Intent.createChooser(shareIntent, "Export blocklist via"))
    }

    private fun confirmRemove(entry: BlocklistEntry) {
        AlertDialog.Builder(this)
            .setTitle("Remove from blocklist?")
            .setMessage("${entry.displayNumber} will no longer be blocked.")
            .setPositiveButton("Remove") { _, _ ->
                manager.unblockNumber(entry.normalizedNumber)
                Toast.makeText(this, "${entry.displayNumber} removed", Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("Clear all blocked numbers?")
            .setMessage("This will remove all ${allEntries.size} blocked numbers. This cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                allEntries.forEach { manager.unblockNumber(it.normalizedNumber) }
                refreshList()
                Toast.makeText(this, "All numbers cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, BlocklistActivity::class.java))
        }
    }
}

private class BlocklistAdapter(
    private var items: List<BlocklistEntry>,
    private val onRemove: (BlocklistEntry) -> Unit,
) : RecyclerView.Adapter<BlocklistAdapter.VH>() {

    fun updateList(newItems: List<BlocklistEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun getItem(position: Int): BlocklistEntry = items[position]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.two_line_list_item, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        holder.title.text = entry.displayNumber.ifBlank { entry.normalizedNumber }
        val dateStr = if (entry.addedTimestamp > 0) {
            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(entry.addedTimestamp))
        } else "Unknown date"
        val reasonLabel = when (entry.addedReason) {
            AddedReason.MANUAL -> "Manual"
            AddedReason.IMPORTED -> "Imported"
            AddedReason.COMMUNITY -> "Community"
            AddedReason.AI_SUGGESTED -> "AI Suggested"
        }
        holder.subtitle.text = "$dateStr · $reasonLabel"
        holder.subtitle.setTextColor(reasonColor(entry.addedReason))
        holder.itemView.setOnLongClickListener {
            onRemove(entry)
            true
        }
    }

    override fun getItemCount() = items.size

    private fun reasonColor(reason: AddedReason): Int = when (reason) {
        AddedReason.MANUAL -> Color.parseColor("#EF4444")
        AddedReason.IMPORTED -> Color.parseColor("#F59E0B")
        AddedReason.COMMUNITY -> Color.parseColor("#A855F7")
        AddedReason.AI_SUGGESTED -> Color.parseColor("#3B82F6")
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(android.R.id.text1)
        val subtitle: TextView = view.findViewById(android.R.id.text2)

        init {
            title.setTextColor(Color.parseColor("#E2E8F0"))
            view.setBackgroundColor(Color.parseColor("#0D1117"))
        }
    }
}

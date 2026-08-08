package com.dialerapp

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.CheckBox
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ContactAdapter
    private lateinit var btnLoad: Button
    private lateinit var btnDial: FloatingActionButton
    private lateinit var btnReload: Button
    private lateinit var btnBrowseFolder: Button
    private lateinit var btnSettings: Button
    private lateinit var statusText: TextView
    private lateinit var checkAutoCall: CheckBox
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    private var contacts: MutableList<Contact> = mutableListOf()
    private var currentIndex: Int = 0

    private val autoCallHandler = Handler(Looper.getMainLooper())
    private var autoCallRunnable: Runnable? = null
    private var countdownSnackbar: Snackbar? = null

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // Persist read permission across reboots
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val fileName = DocumentFile.fromSingleUri(this, it)?.name ?: it.lastPathSegment ?: "file"
            prefs.edit()
                .putString("lastFileUri", it.toString())
                .putString("lastFileName", fileName)
                .apply()
            updateReloadButton(fileName)
            loadExcelFile(it)
        }
    }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit().putString("folderUri", it.toString()).apply()
            showFolderFilePicker(it)
        }
    }

    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) dialCurrent() else Toast.makeText(this, "Phone permission required to auto-dial", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("dialer_state", MODE_PRIVATE)
        recyclerView = findViewById(R.id.recyclerView)
        btnLoad = findViewById(R.id.btnLoad)
        btnDial = findViewById(R.id.btnDial)
        btnReload = findViewById(R.id.btnReload)
        btnBrowseFolder = findViewById(R.id.btnBrowseFolder)
        btnSettings = findViewById(R.id.btnSettings)

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        statusText = findViewById(R.id.statusText)
        checkAutoCall = findViewById(R.id.checkAutoCall)

        checkAutoCall.isChecked = prefs.getBoolean("autoCall", false)
        checkAutoCall.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("autoCall", checked).apply()
        }

        // Restore reload button label if a last file is saved
        prefs.getString("lastFileName", null)?.let { updateReloadButton(it) }

        // Update folder button label if a folder is already granted
        prefs.getString("folderUri", null)?.let {
            val folderName = DocumentFile.fromTreeUri(this, Uri.parse(it))?.name ?: "DialerApp Folder"
            btnBrowseFolder.text = "Browse: $folderName"
        }

        btnLoad.setOnClickListener {
            filePicker.launch(arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel"
            ))
        }

        btnReload.setOnClickListener {
            val uriString = prefs.getString("lastFileUri", null) ?: return@setOnClickListener
            loadExcelFile(Uri.parse(uriString))
        }

        btnBrowseFolder.setOnClickListener {
            val savedFolder = prefs.getString("folderUri", null)
            if (savedFolder != null) {
                showFolderFilePicker(Uri.parse(savedFolder))
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Set DialerApp Folder")
                    .setMessage(
                        "Connect your phone to a PC via USB and create a folder (e.g. Downloads/DialerApp) " +
                        "where you'll place your Excel files.\n\nTap OK to select that folder now."
                    )
                    .setPositiveButton("OK") { _, _ -> folderPicker.launch(null) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        btnDial.setOnClickListener {
            if (currentIndex >= contacts.size) {
                Toast.makeText(this, "Queue complete!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
            } else {
                dialCurrent()
            }
        }

        restoreState()
        refreshUI()
    }

    private fun updateReloadButton(fileName: String) {
        btnReload.text = "Reload: $fileName"
        btnReload.visibility = android.view.View.VISIBLE
    }

    private fun showFolderFilePicker(folderUri: Uri) {
        val folder = DocumentFile.fromTreeUri(this, folderUri)
        if (folder == null || !folder.exists()) {
            Toast.makeText(this, "Folder not accessible. Please re-select it.", Toast.LENGTH_LONG).show()
            prefs.edit().remove("folderUri").apply()
            btnBrowseFolder.text = "Browse DialerApp Folder"
            return
        }

        val excelFiles = folder.listFiles().filter { file ->
            file.isFile && (file.name?.endsWith(".xlsx", ignoreCase = true) == true ||
                            file.name?.endsWith(".xls", ignoreCase = true) == true)
        }

        if (excelFiles.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No Excel Files Found")
                .setMessage("No .xlsx or .xls files were found in the selected folder. Copy your files there and try again.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val names = excelFiles.map { it.name ?: "unknown" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Excel File")
            .setItems(names) { _, which ->
                val selected = excelFiles[which]
                val uri = selected.uri
                prefs.edit()
                    .putString("lastFileUri", uri.toString())
                    .putString("lastFileName", selected.name ?: "file")
                    .apply()
                updateReloadButton(selected.name ?: "file")
                loadExcelFile(uri)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadExcelFile(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
                ?: throw Exception("Cannot open file")

            val workbook = WorkbookFactory.create(inputStream)
            val sheet = workbook.getSheetAt(0)
            val newContacts = mutableListOf<Contact>()

            val headerRow = sheet.getRow(0) ?: throw Exception("Empty sheet")
            var nameCol = -1
            var phoneCol = -1
            for (c in 0 until headerRow.lastCellNum) {
                val header = headerRow.getCell(c)?.stringCellValue?.trim() ?: ""
                when {
                    header.equals("Name", ignoreCase = true) -> nameCol = c
                    header.contains("Phone", ignoreCase = true) ||
                    header.contains("Number", ignoreCase = true) -> phoneCol = c
                }
            }
            if (phoneCol == -1) throw Exception("No 'Phone' or 'PhoneNumber' column found in header row")

            for (i in 1..sheet.lastRowNum) {
                val row = sheet.getRow(i) ?: continue
                val phone = when (row.getCell(phoneCol)?.cellType) {
                    org.apache.poi.ss.usermodel.CellType.NUMERIC ->
                        row.getCell(phoneCol).numericCellValue.toLong().toString()
                    else ->
                        row.getCell(phoneCol)?.stringCellValue?.trim() ?: ""
                }
                if (phone.isBlank()) continue

                val name = if (nameCol >= 0) {
                    row.getCell(nameCol)?.stringCellValue?.trim() ?: "Unknown"
                } else "Unknown"

                newContacts.add(Contact(
                    id = newContacts.size + 1,
                    name = name,
                    phone = phone,
                    status = "pending"
                ))
            }
            workbook.close()
            inputStream.close()

            contacts.clear()
            contacts.addAll(newContacts)
            currentIndex = 0
            saveState()
            refreshUI()

            Toast.makeText(this, "Loaded ${contacts.size} contacts", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun dialCurrent() {
        if (currentIndex >= contacts.size) return
        val contact = contacts[currentIndex]
        contact.status = "dialing"
        saveState()
        refreshUI()

        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:${contact.phone}")
        }
        startActivity(intent)

        contact.status = "dialed"
        saveState()
        refreshUI()
        showDispositionDialog()
    }

    private fun showDispositionDialog() {
        val options = arrayOf("Answered", "No Answer", "Busy", "Callback Later", "Not Interested", "Wrong Number")
        val padding = (20 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding * 2, padding, padding * 2, 0)
        }

        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        options.forEach { option ->
            radioGroup.addView(RadioButton(this).apply {
                text = option
                textSize = 15f
            })
        }
        (radioGroup.getChildAt(0) as RadioButton).isChecked = true
        container.addView(radioGroup)

        container.addView(TextView(this).apply {
            text = "Notes (optional)"
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, padding, 0, 4)
        })

        val notesInput = EditText(this).apply {
            hint = "Write notes here..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
        }
        container.addView(notesInput)

        AlertDialog.Builder(this)
            .setTitle("Call Result")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                if (currentIndex < contacts.size) {
                    val selectedId = radioGroup.checkedRadioButtonId
                    val selectedIndex = radioGroup.indexOfChild(radioGroup.findViewById(selectedId))
                    val contact = contacts[currentIndex]
                    contact.status = options[selectedIndex].lowercase().replace(" ", "_")
                    contact.notes = notesInput.text.toString().trim()
                    contact.calledAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                    currentIndex++
                    saveState()
                    exportResults()
                    refreshUI()
                    if (checkAutoCall.isChecked && currentIndex < contacts.size) {
                        scheduleAutoCall()
                    }
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun exportResults() {
        val folderUriString = prefs.getString("folderUri", null) ?: return
        val folderUri = Uri.parse(folderUriString)
        val folder = DocumentFile.fromTreeUri(this, folderUri) ?: return

        val originalName = prefs.getString("lastFileName", "contacts") ?: "contacts"
        val resultsName = originalName.substringBeforeLast(".") + "_results.xlsx"

        // Delete old results file if it exists
        folder.findFile(resultsName)?.delete()

        val resultsFile = folder.createFile(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            resultsName
        ) ?: return

        try {
            val workbook = XSSFWorkbook()
            val sheet = workbook.createSheet("Results")

            // Header row
            val headerRow = sheet.createRow(0)
            listOf("Name", "Phone", "Status", "Notes", "Called At").forEachIndexed { i, title ->
                headerRow.createCell(i).setCellValue(title)
            }

            // Data rows — only contacts that have been called
            contacts.filter { it.status != "pending" && it.status != "dialing" }
                .forEachIndexed { rowIdx, contact ->
                    val row = sheet.createRow(rowIdx + 1)
                    row.createCell(0).setCellValue(contact.name)
                    row.createCell(1).setCellValue(contact.phone)
                    row.createCell(2).setCellValue(contact.status.replace("_", " "))
                    row.createCell(3).setCellValue(contact.notes)
                    row.createCell(4).setCellValue(contact.calledAt)
                }

            for (i in 0..4) sheet.autoSizeColumn(i)

            contentResolver.openOutputStream(resultsFile.uri)?.use { workbook.write(it) }
            workbook.close()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not save results: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun scheduleAutoCall(secondsLeft: Int = prefs.getInt("autoCallDelay", 5)) {
        cancelAutoCall()
        val nextName = contacts.getOrNull(currentIndex)?.name ?: "next contact"
        val rootView = findViewById<android.view.View>(android.R.id.content)

        countdownSnackbar = Snackbar.make(
            rootView,
            "Calling $nextName in ${secondsLeft}s…",
            Snackbar.LENGTH_INDEFINITE
        ).setAction("Cancel") {
            cancelAutoCall()
        }
        countdownSnackbar?.show()

        autoCallRunnable = Runnable {
            if (secondsLeft > 1) {
                scheduleAutoCall(secondsLeft - 1)
            } else {
                countdownSnackbar?.dismiss()
                countdownSnackbar = null
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                } else {
                    dialCurrent()
                }
            }
        }
        autoCallHandler.postDelayed(autoCallRunnable!!, 1000)
    }

    private fun cancelAutoCall() {
        autoCallRunnable?.let { autoCallHandler.removeCallbacks(it) }
        autoCallRunnable = null
        countdownSnackbar?.dismiss()
        countdownSnackbar = null
    }

    private fun refreshUI() {
        adapter.updateData(contacts, currentIndex)

        val remaining = contacts.size - currentIndex
        statusText.text = if (contacts.isEmpty()) {
            "Load an Excel file to start"
        } else if (remaining <= 0) {
            "Queue complete! ${contacts.size} contacts processed."
        } else {
            "Contact ${currentIndex + 1} of ${contacts.size}  •  $remaining remaining"
        }

        val dialEnabled = contacts.isNotEmpty() && currentIndex < contacts.size
        btnDial.isEnabled = dialEnabled
        btnDial.alpha = if (dialEnabled) 1.0f else 0.4f

        if (currentIndex < contacts.size) {
            recyclerView.scrollToPosition(currentIndex)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAutoCall()
    }

    private fun saveState() {
        prefs.edit()
            .putString("contacts", gson.toJson(contacts))
            .putInt("currentIndex", currentIndex)
            .apply()
    }

    private fun restoreState() {
        val json = prefs.getString("contacts", null)
        if (json != null) {
            val type = object : TypeToken<MutableList<Contact>>() {}.type
            val restored: MutableList<Contact> = gson.fromJson(json, type)
            contacts.clear()
            contacts.addAll(restored)
            currentIndex = prefs.getInt("currentIndex", 0)
        }

        adapter = ContactAdapter(contacts, currentIndex) { position ->
            currentIndex = position
            refreshUI()
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }
}

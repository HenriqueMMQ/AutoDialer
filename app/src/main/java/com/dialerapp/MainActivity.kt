package com.dialerapp

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.core.content.FileProvider
import com.google.android.material.snackbar.Snackbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.edit

class MainActivity : AppCompatActivity()
{
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ContactAdapter
    private lateinit var btnLoad: Button
    private lateinit var btnDial: FloatingActionButton
    private lateinit var btnReload: Button
    private lateinit var btnShareResults: Button
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

    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

    companion object
    {
        private const val POLL_INTERVAL_MS = 3000L
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument())
    { uri ->
        uri?.let()
        {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val fileName = it.lastPathSegment?.substringAfterLast("/") ?: getString(R.string.file_fallback_name)
            prefs.edit()
                .putString("lastFileUri", it.toString())
                .putString("lastFileName", fileName)
                .apply()
            updateReloadButton(fileName)
            loadExcelFile(it)
        }
    }

    private val callPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission())
    {
        granted ->
            if (granted)
                dialCurrent()
            else
                Toast.makeText(this, R.string.toast_permission_required, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("dialer_state", MODE_PRIVATE)
        recyclerView = findViewById(R.id.recyclerView)
        btnLoad = findViewById(R.id.btnLoad)
        btnDial = findViewById(R.id.btnDial)
        btnReload = findViewById(R.id.btnReload)
        btnShareResults = findViewById(R.id.btnShareResults)
        btnSettings = findViewById(R.id.btnSettings)

        btnSettings.setOnClickListener()
        {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        statusText = findViewById(R.id.statusText)
        checkAutoCall = findViewById(R.id.checkAutoCall)

        checkAutoCall.isChecked = prefs.getBoolean("autoCall", false)
        checkAutoCall.setOnCheckedChangeListener()
        { _, checked ->
            prefs.edit().putBoolean("autoCall", checked).apply()
        }

        prefs.getString("lastFileName", null)?.let { updateReloadButton(it) }

        btnLoad.setOnClickListener()
        {
            filePicker.launch(arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel"
            ))
        }

        btnReload.setOnClickListener()
        {
            val uriString = prefs.getString("lastFileUri", null) ?: return@setOnClickListener
            loadExcelFile(Uri.parse(uriString))
        }

        btnShareResults.setOnClickListener { shareResults() }

        btnDial.setOnClickListener()
        {
            if (currentIndex >= contacts.size)
            {
                Toast.makeText(this, R.string.toast_queue_complete, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
                callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
            else
                dialCurrent()
        }

        restoreState()

        if (savedInstanceState == null)
            handleIncomingIntent(intent)

        refreshUI()
    }

    override fun onNewIntent(intent: Intent)
    {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent)
    {
        val uri: Uri? = when (intent.action)
        {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
            {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else
                {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            else -> null
        }
        if (uri != null)
        {
            val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: getString(R.string.file_fallback_name)
            prefs.edit()
            {
                putString("lastFileUri", uri.toString())
                    .putString("lastFileName", fileName)
            }
            updateReloadButton(fileName)
            loadExcelFile(uri)
            intent.action = null
        }
    }

    private fun updateReloadButton(fileName: String)
    {
        btnReload.text = getString(R.string.btn_reload, fileName)
        btnReload.visibility = android.view.View.VISIBLE
    }

    private fun loadExcelFile(uri: Uri)
    {
        try
        {
            val inputStream = contentResolver.openInputStream(uri) ?: throw Exception(getString(R.string.error_cannot_open))

            val ext = prefs.getString("lastFileName", "ficheiro.xlsx")?.substringAfterLast(".", "xlsx") ?: "xlsx"
            val templateFile = File(getExternalFilesDir(null), "template.$ext")
            FileOutputStream(templateFile).use { inputStream.copyTo(it) }
            inputStream.close()

            val workbook = WorkbookFactory.create(templateFile)
            val sheet = workbook.getSheetAt(0)
            val newContacts = mutableListOf<Contact>()

            val headerRow = sheet.getRow(0) ?: throw Exception(getString(R.string.error_empty_sheet))
            var nameCol = -1
            var phoneCol = -1
            for (c in 0 until headerRow.lastCellNum)
            {
                val header = headerRow.getCell(c)?.stringCellValue?.trim() ?: ""
                when
                {
                    header.equals("Name", ignoreCase = true) ||
                    header.equals("Nome", ignoreCase = true) -> nameCol = c
                    header.contains("Phone", ignoreCase = true) ||
                    header.contains("Number", ignoreCase = true) ||
                    header.contains("Telefone", ignoreCase = true) ||
                    header.contains("Número", ignoreCase = true) -> phoneCol = c
                }
            }
            if (phoneCol == -1)
                throw Exception(getString(R.string.error_no_phone_column))

            for (i in 1..sheet.lastRowNum)
            {
                val row = sheet.getRow(i) ?: continue
                val phone = when (row.getCell(phoneCol)?.cellType)
                {
                    org.apache.poi.ss.usermodel.CellType.NUMERIC ->
                        row.getCell(phoneCol).numericCellValue.toLong().toString()
                    else ->
                        row.getCell(phoneCol)?.stringCellValue?.trim() ?: ""
                }
                if (phone.isBlank())
                    continue

                val contactName = if (nameCol >= 0)
                {
                    row.getCell(nameCol)?.stringCellValue?.trim() ?: getString(R.string.unknown_contact)
                }
                else
                    getString(R.string.unknown_contact)

                newContacts.add(Contact(
                    id = newContacts.size + 1,
                    name = contactName,
                    phone = phone,
                    status = "pending",
                    source = "app_excel"
                ))
            }
            workbook.close()

            contacts.clear()
            contacts.addAll(newContacts)
            currentIndex = 0
            saveState()
            refreshUI()
            pushContactsToServer()

            Toast.makeText(this, getString(R.string.toast_loaded, contacts.size), Toast.LENGTH_SHORT).show()

        }
        catch (e: Exception)
        {
            Toast.makeText(this, getString(R.string.toast_error, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun dialCurrent()
    {
        if (currentIndex >= contacts.size)
            return
        val contact = contacts[currentIndex]
        contact.status = "dialing"
        saveState()
        refreshUI()

        val intent = Intent(Intent.ACTION_CALL).apply()
        {
            data = Uri.parse("tel:${contact.phone}")
        }
        startActivity(intent)

        contact.status = "dialed"
        saveState()
        refreshUI()
        showDispositionDialog()
    }

    private fun showDispositionDialog()
    {
        val options = arrayOf(
            getString(R.string.dialog_option_answered),
            getString(R.string.dialog_option_no_answer),
            getString(R.string.dialog_option_busy),
            getString(R.string.dialog_option_callback),
            getString(R.string.dialog_option_not_interested),
            getString(R.string.dialog_option_wrong_number)
        )
        val statusKeys = arrayOf("answered", "no_answer", "busy", "callback_later", "not_interested", "wrong_number")
        val padding = (20 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(this).apply()
        {
            orientation = LinearLayout.VERTICAL
            setPadding(padding * 2, padding, padding * 2, 0)
        }

        val radioGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        options.forEach()
        { option ->
            radioGroup.addView(RadioButton(this).apply()
            {
                text = option
                textSize = 15f
            })
        }
        (radioGroup.getChildAt(0) as RadioButton).isChecked = true
        container.addView(radioGroup)

        container.addView(TextView(this).apply()
        {
            text = getString(R.string.dialog_notes_label)
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, padding, 0, 4)
        })

        val notesInput = EditText(this).apply()
        {
            hint = getString(R.string.dialog_notes_hint)
            this.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            this.minLines = 2
            this.maxLines = 4
        }
        container.addView(notesInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_call_result)
            .setView(container)
            .setPositiveButton(R.string.dialog_save)
            { _, _ ->
                if (currentIndex < contacts.size)
                {
                    val selectedId = radioGroup.checkedRadioButtonId
                    val selectedIndex = radioGroup.indexOfChild(radioGroup.findViewById(selectedId))
                    val contact = contacts[currentIndex]
                    contact.status = statusKeys[selectedIndex]
                    contact.notes = notesInput.text.toString().trim()
                    contact.calledAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                    currentIndex++
                    saveState()
                    exportResultsSilently()
                    refreshUI()
                    reportCallResult(contact)
                    if (checkAutoCall.isChecked && currentIndex < contacts.size)
                    {
                        scheduleAutoCall()
                    }
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun resultsFile(): File
    {
        val originalName = prefs.getString("lastFileName", "contactos") ?: "contactos"
        val resultsName = originalName.substringBeforeLast(".") + "_resultados.xlsx"
        return File(getExternalFilesDir(null), resultsName)
    }

    private fun statusLabel(status: String): String = when (status)
    {
        "answered"       -> getString(R.string.status_answered)
        "no_answer"      -> getString(R.string.status_no_answer)
        "busy"           -> getString(R.string.status_busy)
        "callback_later" -> getString(R.string.status_callback_later)
        "not_interested" -> getString(R.string.status_not_interested)
        "wrong_number"   -> getString(R.string.status_wrong_number)
        "dialed"         -> getString(R.string.status_dialed)
        else             -> status.replace("_", " ")
    }

    private fun exportResultsSilently()
    {
        try
        {
            val ext = prefs.getString("lastFileName", "ficheiro.xlsx")
                ?.substringAfterLast(".", "xlsx") ?: "xlsx"
            val templateFile = File(getExternalFilesDir(null), "template.$ext")

            val workbook = if (templateFile.exists())
            {
                val wb = WorkbookFactory.create(templateFile)
                val sheet = wb.getSheetAt(0)
                val headerRow = sheet.getRow(0) ?: sheet.createRow(0)
                val existingCols = (0 until headerRow.lastCellNum).associate {
                    headerRow.getCell(it)?.stringCellValue?.trim() to it
                }
                fun col(colName: String): Int = existingCols[colName] ?: run()
                {
                    val idx = headerRow.lastCellNum.toInt()
                    headerRow.createCell(idx).setCellValue(colName)
                    idx
                }
                val statusCol   = col(getString(R.string.col_status))
                val notesCol    = col(getString(R.string.col_notes))
                val calledAtCol = col(getString(R.string.col_called_at))

                contacts.forEachIndexed()
                { idx, contact ->
                    if (contact.status == "pending" || contact.status == "dialing") return@forEachIndexed
                    val row = sheet.getRow(idx + 1) ?: sheet.createRow(idx + 1)
                    row.createCell(statusCol).setCellValue(statusLabel(contact.status))
                    row.createCell(notesCol).setCellValue(contact.notes)
                    row.createCell(calledAtCol).setCellValue(contact.calledAt)
                }
                wb
            }
            else
            {
                val wb = org.apache.poi.xssf.usermodel.XSSFWorkbook()
                val sheet = wb.createSheet(getString(R.string.sheet_results))
                val headerRow = sheet.createRow(0)
                listOf(
                    getString(R.string.col_name),
                    getString(R.string.col_phone),
                    getString(R.string.col_status),
                    getString(R.string.col_notes),
                    getString(R.string.col_called_at)
                ).forEachIndexed { i, title -> headerRow.createCell(i).setCellValue(title) }
                contacts.filter { it.status != "pending" && it.status != "dialing" }
                    .forEachIndexed()
                    { rowIdx, contact ->
                        val row = sheet.createRow(rowIdx + 1)
                        row.createCell(0).setCellValue(contact.name)
                        row.createCell(1).setCellValue(contact.phone)
                        row.createCell(2).setCellValue(statusLabel(contact.status))
                        row.createCell(3).setCellValue(contact.notes)
                        row.createCell(4).setCellValue(contact.calledAt)
                    }
                wb
            }

            FileOutputStream(resultsFile()).use { workbook.write(it) }
            workbook.close()
        }
        catch (e: Exception)
        {
            Toast.makeText(this, getString(R.string.toast_export_error, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun shareResults()
    {
        val hasProcessed = contacts.any { it.status != "pending" && it.status != "dialing" }
        if (!hasProcessed)
        {
            Toast.makeText(this, R.string.toast_no_results, Toast.LENGTH_SHORT).show()
            return
        }
        exportResultsSilently()
        val file = resultsFile()
        if (!file.exists())
        {
            Toast.makeText(this, R.string.toast_results_error, Toast.LENGTH_SHORT).show()
            return
        }
        val fileUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply()
        {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser_title)))
    }

    private fun scheduleAutoCall(secondsLeft: Int = prefs.getInt("autoCallDelay", 5))
    {
        cancelAutoCall()
        val nextName = contacts.getOrNull(currentIndex)?.name
            ?: getString(R.string.next_contact_fallback)
        val rootView = findViewById<android.view.View>(android.R.id.content)

        countdownSnackbar = Snackbar.make(
            rootView,
            getString(R.string.snackbar_calling, nextName, secondsLeft),
            Snackbar.LENGTH_INDEFINITE
        ).setAction(R.string.snackbar_cancel)
        {
            cancelAutoCall()
        }
        countdownSnackbar?.show()

        autoCallRunnable = Runnable()
        {
            if (secondsLeft > 1)
                scheduleAutoCall(secondsLeft - 1)
            else
            {
                countdownSnackbar?.dismiss()
                countdownSnackbar = null
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
                    callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                else
                    dialCurrent()
            }
        }
        autoCallHandler.postDelayed(autoCallRunnable!!, 1000)
    }

    private fun cancelAutoCall()
    {
        autoCallRunnable?.let { autoCallHandler.removeCallbacks(it) }
        autoCallRunnable = null
        countdownSnackbar?.dismiss()
        countdownSnackbar = null
    }

    private fun refreshUI()
    {
        adapter.updateData(contacts, currentIndex)

        val remaining = contacts.size - currentIndex
        statusText.text = when
        {
            contacts.isEmpty() -> getString(R.string.status_empty)
            remaining <= 0     -> getString(R.string.status_complete, contacts.size)
            else               -> getString(R.string.status_progress, currentIndex + 1, contacts.size, remaining)
        }

        val dialEnabled = contacts.isNotEmpty() && currentIndex < contacts.size
        btnDial.isEnabled = dialEnabled
        btnDial.alpha = if (dialEnabled) 1.0f else 0.4f

        val hasResults = contacts.any { it.status != "pending" && it.status != "dialing" }
        btnShareResults.visibility = if (hasResults) android.view.View.VISIBLE else android.view.View.GONE

        if (currentIndex < contacts.size)
        {
            recyclerView.scrollToPosition(currentIndex)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean
    {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean
    {
        return when (item.itemId)
        {
            R.id.action_settings ->
            {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume()
    {
        super.onResume()
        startPolling()
    }

    override fun onPause()
    {
        super.onPause()
        stopPolling()
    }

    override fun onDestroy()
    {
        super.onDestroy()
        cancelAutoCall()
        stopPolling()
    }

    private fun startPolling()
    {
        stopPolling()
        val serverUrl = prefs.getString("serverUrl", "") ?: ""
        if (serverUrl.isEmpty()) return
        pollRunnable = object : Runnable
        {
            override fun run()
            {
                doPoll()
                pollHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        pollHandler.post(pollRunnable!!)
    }

    private fun stopPolling()
    {
        pollRunnable?.let { pollHandler.removeCallbacks(it) }
        pollRunnable = null
    }

    private fun doPoll()
    {
        val serverUrl = prefs.getString("serverUrl", "")?.trimEnd('/') ?: return
        if (serverUrl.isEmpty()) return
        val deviceId = getOrCreateDeviceId()
        Thread {
            try
            {
                val url = java.net.URL("$serverUrl/api/dial/next?deviceId=$deviceId")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout    = 2000
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = gson.fromJson(body, com.google.gson.JsonObject::class.java)

                val pendingContacts = json.get("pendingContacts")
                if (pendingContacts != null && !pendingContacts.isJsonNull)
                {
                    val arr = pendingContacts.asJsonObject.get("contacts")?.asJsonArray
                    if (arr != null)
                    {
                        val type = object : com.google.gson.reflect.TypeToken<MutableList<Contact>>() {}.type
                        val incoming: MutableList<Contact> = gson.fromJson(arr, type)
                        pollHandler.post { applyRemoteContacts(incoming) }
                    }
                }

                val pending = json.get("pending")
                if (pending != null && !pending.isJsonNull)
                {
                    val obj   = pending.asJsonObject
                    val phone = obj.get("phone")?.asString ?: return@Thread
                    val name  = obj.get("name")?.asString  ?: phone
                    pollHandler.post { handleRemoteDial(phone, name) }
                }
            }
            catch (_: Exception) { }
        }.start()
    }

    private fun getOrCreateDeviceId(): String
    {
        var id = prefs.getString("deviceId", null)
        if (id == null)
        {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("deviceId", id).apply()
        }
        return id
    }

    private fun applyRemoteContacts(incoming: MutableList<Contact>)
    {
        contacts.clear()
        contacts.addAll(incoming)
        currentIndex = 0
        saveState()
        refreshUI()
        Toast.makeText(this, getString(R.string.toast_contacts_synced, contacts.size), Toast.LENGTH_SHORT).show()
    }

    private fun handleRemoteDial(phone: String, name: String)
    {
        val cleanPhone = phone.replace("\\s".toRegex(), "")
        val idx = contacts.indexOfFirst { it.phone.replace("\\s".toRegex(), "") == cleanPhone }
        if (idx >= 0)
        {
            currentIndex = idx
        }
        else
        {
            // Not in local list — add a temporary contact so dialCurrent() works
            val temp = Contact(
                id = contacts.size + 1,
                name = name,
                phone = phone,
                status = "pending",
                source = "remote_dial"
            )
            contacts.add(temp)
            currentIndex = contacts.size - 1
            saveState()
            refreshUI()
        }
        Toast.makeText(this, getString(R.string.toast_remote_dial, name), Toast.LENGTH_SHORT).show()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
        else
            dialCurrent()
    }

    private fun reportCallResult(contact: Contact)
    {
        val serverUrl = prefs.getString("serverUrl", "")?.trimEnd('/') ?: return
        if (serverUrl.isEmpty()) return
        val payload = gson.toJson(mapOf(
            "contactId" to contact.id,
            "phone"     to contact.phone,
            "status"    to contact.status,
            "notes"     to contact.notes,
            "calledAt"  to contact.calledAt
        ))
        Thread {
            try
            {
                val url  = java.net.URL("$serverUrl/api/dial/result")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 3000
                conn.readTimeout    = 3000
                conn.outputStream.use { it.write(payload.toByteArray()) }
                conn.inputStream.bufferedReader().readText()
                conn.disconnect()
            }
            catch (_: Exception) { }
        }.start()
    }

    private fun pushContactsToServer()
    {
        val serverUrl = prefs.getString("serverUrl", "")?.trimEnd('/') ?: return
        if (serverUrl.isEmpty()) return
        val deviceId = getOrCreateDeviceId()
        val payload = gson.toJson(mapOf(
            "deviceId" to deviceId,
            "contacts" to contacts.map { mapOf("id" to it.id, "name" to it.name, "phone" to it.phone, "status" to it.status) }
        ))
        Thread {
            try
            {
                val url  = java.net.URL("$serverUrl/api/device/contacts")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 3000
                conn.readTimeout    = 3000
                conn.outputStream.use { it.write(payload.toByteArray()) }
                conn.inputStream.bufferedReader().readText()
                conn.disconnect()
            }
            catch (_: Exception) { }
        }.start()
    }

    private fun showContactProfileDialog(position: Int)
    {
        val contact = contacts.getOrNull(position) ?: return
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
        }

        fun labeledField(label: String, value: String, inputType: Int): EditText {
            container.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(0xFF546E7A.toInt())
                setPadding(0, (8 * dp).toInt(), 0, (2 * dp).toInt())
            })
            return EditText(this).apply {
                setText(value)
                this.inputType = inputType
                textSize = 15f
                container.addView(this)
            }
        }

        // Source chip — guard against null for contacts persisted before this field existed
        val source = contact.source.orEmpty()
        val sourceLabel = when (source) {
            "app_excel"         -> getString(R.string.source_app_excel)
            "backoffice_excel"  -> getString(R.string.source_backoffice_excel)
            "backoffice_manual" -> getString(R.string.source_backoffice_manual)
            "remote_dial"       -> getString(R.string.source_remote_dial)
            else                -> getString(R.string.source_unknown)
        }
        container.addView(TextView(this).apply {
            text = sourceLabel
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(when (source) {
                "app_excel"         -> 0xFF1565C0.toInt()
                "backoffice_excel"  -> 0xFF2E7D32.toInt()
                "backoffice_manual" -> 0xFF6A1B9A.toInt()
                "remote_dial"       -> 0xFFE65100.toInt()
                else                -> 0xFF546E7A.toInt()
            })
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (12 * dp).toInt()
            layoutParams = lp
        })

        val nameField  = labeledField(getString(R.string.col_name),  contact.name,  InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val phoneField = labeledField(getString(R.string.col_phone), contact.phone, InputType.TYPE_CLASS_PHONE)
        val notesField = labeledField(getString(R.string.dialog_notes_label), contact.notes,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        notesField.minLines = 2
        notesField.maxLines = 4

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.profile_title, contact.id))
            .setView(container)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val newName  = nameField.text.toString().trim()
                val newPhone = phoneField.text.toString().trim()
                val newNotes = notesField.text.toString().trim()
                if (newName.isNotEmpty() && newPhone.isNotEmpty()) {
                    contacts[position] = contact.copy(name = newName, phone = newPhone, notes = newNotes)
                    saveState()
                    refreshUI()
                    exportResultsSilently()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveState()
    {
        prefs.edit()
            .putString("contacts", gson.toJson(contacts))
            .putInt("currentIndex", currentIndex)
            .apply()
    }

    private fun restoreState()
    {
        val json = prefs.getString("contacts", null)
        if (json != null)
        {
            val type = object : TypeToken<MutableList<Contact>>() {}.type
            val restored: MutableList<Contact> = gson.fromJson(json, type)
            contacts.clear()
            contacts.addAll(restored)
            currentIndex = prefs.getInt("currentIndex", 0)
        }

        adapter = ContactAdapter(
            contacts, currentIndex,
            onItemClick = { position ->
                currentIndex = position
                refreshUI()
            },
            onItemLongClick = { position ->
                showContactProfileDialog(position)
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }
}

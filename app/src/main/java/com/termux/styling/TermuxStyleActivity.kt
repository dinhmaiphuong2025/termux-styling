package com.termux.styling

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.AtomicFile
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.*

const val DEFAULT_FILENAME = "Default"
private const val REQUEST_CODE_PICK_FONT = 2001

fun capitalize(str: String): String {
    var lastWhitespace = true
    val chars = str.toCharArray()
    for (i in chars.indices) {
        if (Character.isLetter(chars[i])) {
            if (lastWhitespace) chars[i] = Character.toUpperCase(chars[i])
            lastWhitespace = false
        } else {
            lastWhitespace = Character.isWhitespace(chars[i])
        }
    }
    return String(chars)
}

class TermuxStyleActivity : Activity() {

    enum class ItemType {
        ACTION_IMPORT,
        DEFAULT,
        CUSTOM,
        BUILT_IN
    }

    internal class Selectable(
        val fileName: String,
        val itemType: ItemType = ItemType.BUILT_IN,
        val customFile: File? = null,
        val customDisplayName: String? = null
    ) {
        val displayName: String = customDisplayName ?: run {
            if (itemType == ItemType.ACTION_IMPORT || itemType == ItemType.DEFAULT) {
                fileName
            } else {
                var name = fileName.replace('-', ' ')
                val dotIndex = name.lastIndexOf('.')
                if (dotIndex != -1) name = name.substring(0, dotIndex)
                capitalize(name)
            }
        }

        override fun toString(): String {
            return displayName
        }
    }

    private lateinit var fontAdapter: ArrayAdapter<Selectable>
    private lateinit var colorAdapter: ArrayAdapter<Selectable>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Avoid dim behind:
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setContentView(R.layout.layout)

        val colorSpinner = findViewById<Button>(R.id.color_spinner)
        val fontSpinner = findViewById<Button>(R.id.font_spinner)

        colorAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item)
        colorSpinner.setOnClickListener {
            val dialog = AlertDialog.Builder(this@TermuxStyleActivity)
                .setAdapter(colorAdapter) { _, which ->
                    val item = colorAdapter.getItem(which)
                    copyColorFile(item)
                }
                .create()

            dialog.setOnShowListener {
                val lv = dialog.listView
                lv.setOnItemLongClickListener { _, _, position, _ ->
                    val item = colorAdapter.getItem(position)
                    if (item != null && item.itemType == ItemType.BUILT_IN) {
                        showLicense(item, true)
                        true
                    } else {
                        false
                    }
                }
            }
            dialog.show()
        }

        fontAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item)
        fontSpinner.setOnClickListener {
            val dialog = AlertDialog.Builder(this@TermuxStyleActivity)
                .setAdapter(fontAdapter) { _, which ->
                    val item = fontAdapter.getItem(which) ?: return@setAdapter
                    when (item.itemType) {
                        ItemType.ACTION_IMPORT -> openFontPicker()
                        ItemType.DEFAULT -> copyDefaultFont()
                        ItemType.CUSTOM -> copyCustomFont(item.customFile, item.displayName)
                        ItemType.BUILT_IN -> copyBuiltInFont(item.fileName)
                    }
                }
                .create()

            dialog.setOnShowListener {
                val lv = dialog.listView
                lv.setOnItemLongClickListener { _, _, position, _ ->
                    val item = fontAdapter.getItem(position) ?: return@setOnItemLongClickListener false
                    when (item.itemType) {
                        ItemType.BUILT_IN -> {
                            showLicense(item, false)
                            true
                        }
                        ItemType.CUSTOM -> {
                            showDeleteCustomFontDialog(item)
                            true
                        }
                        else -> false
                    }
                }
            }
            dialog.show()
        }

        loadColorList()
        loadFontList()
    }

    private fun getTermuxDir(): File {
        val termuxPackageContext = createPackageContext("com.termux", Context.CONTEXT_IGNORE_SECURITY)
        val homeDir = File(termuxPackageContext.filesDir, "home")
        val termuxDir = File(homeDir, ".termux")
        if (!(termuxDir.isDirectory || termuxDir.mkdirs())) {
            throw RuntimeException("Cannot create termux dir=" + termuxDir.absolutePath)
        }
        termuxDir.setWritable(true)
        termuxDir.setExecutable(true)
        return termuxDir
    }

    private fun getCustomFontsDir(): File {
        val termuxDir = getTermuxDir()
        val customFontsDir = File(termuxDir, "fonts")
        if (!customFontsDir.exists()) {
            customFontsDir.mkdirs()
        }
        customFontsDir.setWritable(true)
        customFontsDir.setExecutable(true)
        return customFontsDir
    }

    private fun loadColorList() {
        val colorList = ArrayList<Selectable>()
        colorList.add(Selectable(DEFAULT_FILENAME, ItemType.DEFAULT))

        try {
            assets.list("colors")!!
                .filter { it.endsWith(".properties") }
                .forEach { colorList.add(Selectable(it, ItemType.BUILT_IN)) }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }

        colorAdapter.clear()
        colorAdapter.addAll(colorList)
    }

    private fun loadFontList() {
        val fontList = ArrayList<Selectable>()

        // 1. Action to import custom font
        fontList.add(Selectable(getString(R.string.import_custom_font), ItemType.ACTION_IMPORT))

        // 2. Default font
        fontList.add(Selectable(DEFAULT_FILENAME, ItemType.DEFAULT))

        // 3. User custom fonts stored in ~/.termux/fonts/
        try {
            val customFontsDir = getCustomFontsDir()
            val files = customFontsDir.listFiles { file ->
                val name = file.name.lowercase(Locale.ROOT)
                file.isFile && (name.endsWith(".ttf") || name.endsWith(".otf"))
            }
            files?.sortedBy { it.name.lowercase(Locale.ROOT) }?.forEach { file ->
                fontList.add(Selectable(file.name, ItemType.CUSTOM, file, "Custom: " + file.nameWithoutExtension))
            }
        } catch (e: Exception) {
            Log.w("termux", "Failed to list custom fonts", e)
        }

        // 4. Built-in fonts from assets
        try {
            assets.list("fonts")!!
                .filter { it.endsWith(".ttf") }
                .forEach { fontList.add(Selectable(it, ItemType.BUILT_IN)) }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }

        fontAdapter.clear()
        fontAdapter.addAll(fontList)
    }

    private fun openFontPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "font/*",
                    "font/ttf",
                    "font/otf",
                    "application/font-sfnt",
                    "application/x-font-ttf",
                    "application/x-font-truetype",
                    "application/x-font-opentype",
                    "application/octet-stream"
                )
            )
        }

        try {
            startActivityForResult(intent, REQUEST_CODE_PICK_FONT)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
            }
            try {
                startActivityForResult(fallbackIntent, REQUEST_CODE_PICK_FONT)
            } catch (e2: Exception) {
                Toast.makeText(this, "Cannot open file picker: " + e2.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_FONT && resultCode == Activity.RESULT_OK) {
            val uri: Uri = data?.data ?: return
            handleImportedFontUri(uri)
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            result = cursor.getString(nameIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("termux", "Failed to query display name from uri", e)
            }
        }

        if (result.isNullOrEmpty()) {
            result = uri.lastPathSegment
        }

        if (result.isNullOrEmpty()) {
            result = "imported_font_" + System.currentTimeMillis() + ".ttf"
        }

        val lower = result!!.lowercase(Locale.ROOT)
        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) {
            result = "$result.ttf"
        }

        return result!!
    }

    private fun isValidFontFile(file: File): Boolean {
        if (!file.exists() || file.length() < 12) return false
        try {
            val magic = ByteArray(4)
            file.inputStream().use { it.read(magic) }

            val isTrueType = (magic[0] == 0x00.toByte() && magic[1] == 0x01.toByte() && magic[2] == 0x00.toByte() && magic[3] == 0x00.toByte()) ||
                    (magic[0] == 't'.code.toByte() && magic[1] == 'r'.code.toByte() && magic[2] == 'u'.code.toByte() && magic[3] == 'e'.code.toByte())
            val isOpenType = (magic[0] == 'O'.code.toByte() && magic[1] == 'T'.code.toByte() && magic[2] == 'T'.code.toByte() && magic[3] == 'O'.code.toByte())
            val isTtc = (magic[0] == 't'.code.toByte() && magic[1] == 't'.code.toByte() && magic[2] == 'c'.code.toByte() && magic[3] == 'f'.code.toByte())

            if (!isTrueType && !isOpenType && !isTtc) {
                return false
            }

            val typeface = Typeface.createFromFile(file)
            return typeface != null
        } catch (t: Throwable) {
            Log.w("termux", "Font validation error", t)
            return false
        }
    }

    private fun handleImportedFontUri(uri: Uri) {
        val fileName = getFileNameFromUri(uri)
        val tempFile = File(cacheDir, "temp_import_" + System.currentTimeMillis() + ".font")

        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Toast.makeText(this, R.string.invalid_font_file, Toast.LENGTH_LONG).show()
                return
            }

            if (!isValidFontFile(tempFile)) {
                tempFile.delete()
                Toast.makeText(this, R.string.invalid_font_file, Toast.LENGTH_LONG).show()
                return
            }

            val customFontsDir = getCustomFontsDir()
            val targetCustomFile = File(customFontsDir, fileName)
            tempFile.copyTo(targetCustomFile, overwrite = true)
            tempFile.delete()

            targetCustomFile.setReadable(true, false)
            targetCustomFile.setWritable(true, true)

            // Apply immediately to font.ttf
            copyCustomFont(targetCustomFile, targetCustomFile.nameWithoutExtension)

            // Reload font list
            loadFontList()
        } catch (e: Exception) {
            Log.w("termux", "Failed to import font", e)
            if (tempFile.exists()) tempFile.delete()
            Toast.makeText(this, getString(R.string.writing_failed) + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun showDeleteCustomFontDialog(item: Selectable) {
        val file = item.customFile ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_custom_font_title)
            .setMessage(getString(R.string.delete_custom_font_message, item.displayName))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (file.delete()) {
                    Toast.makeText(this, R.string.font_deleted, Toast.LENGTH_SHORT).show()
                    loadFontList()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLicense(mCurrentSelectable: Selectable, colors: Boolean) {
        try {
            val assetsFolder = if (colors) "colors" else "fonts"
            var fileName = mCurrentSelectable.fileName
            val dotIndex = fileName.lastIndexOf('.')
            if (dotIndex != -1) fileName = fileName.substring(0, dotIndex)
            fileName += ".txt"

            assets.open("$assetsFolder/$fileName").use { `in` ->
                val buffer = ByteArray(`in`.available())
                `in`.read(buffer)
                val license = SpannableString(String(buffer))
                Linkify.addLinks(license, Linkify.ALL)
                val dialog = AlertDialog.Builder(this)
                    .setTitle(mCurrentSelectable.displayName)
                    .setMessage(license)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                (dialog.findViewById<View>(android.R.id.message) as TextView).movementMethod = LinkMovementMethod.getInstance()
            }
        } catch (e: IOException) {
            // Ignore.
        }
    }

    private fun copyDefaultFont() {
        try {
            val termuxDir = getTermuxDir()
            val destinationFile = File(termuxDir, "font.ttf").canonicalFile
            destinationFile.setWritable(true)
            destinationFile.parentFile?.setWritable(true)
            destinationFile.parentFile?.setExecutable(true)

            val atomicFile = AtomicFile(destinationFile)
            val out = atomicFile.startWrite()
            // Just leave an empty font file as a marker for default monospace
            atomicFile.finishWrite(out)

            sendReloadBroadcast(false)
            Toast.makeText(this, getString(R.string.font_applied, DEFAULT_FILENAME), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w("termux", "Failed to write font.ttf", e)
            val message = resources.getString(R.string.writing_failed) + e.message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun copyBuiltInFont(fileName: String) {
        try {
            val termuxDir = getTermuxDir()
            val destinationFile = File(termuxDir, "font.ttf").canonicalFile
            destinationFile.setWritable(true)
            destinationFile.parentFile?.setWritable(true)
            destinationFile.parentFile?.setExecutable(true)

            val atomicFile = AtomicFile(destinationFile)
            val out = atomicFile.startWrite()
            assets.open("fonts/$fileName").use { it.copyTo(out) }
            atomicFile.finishWrite(out)

            sendReloadBroadcast(false)
            val displayName = Selectable(fileName, ItemType.BUILT_IN).displayName
            Toast.makeText(this, getString(R.string.font_applied, displayName), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w("termux", "Failed to write font.ttf", e)
            val message = resources.getString(R.string.writing_failed) + e.message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun copyCustomFont(file: File?, displayName: String) {
        if (file == null || !file.exists()) return
        try {
            val termuxDir = getTermuxDir()
            val destinationFile = File(termuxDir, "font.ttf").canonicalFile
            destinationFile.setWritable(true)
            destinationFile.parentFile?.setWritable(true)
            destinationFile.parentFile?.setExecutable(true)

            val atomicFile = AtomicFile(destinationFile)
            val out = atomicFile.startWrite()
            file.inputStream().use { it.copyTo(out) }
            atomicFile.finishWrite(out)

            sendReloadBroadcast(false)
            Toast.makeText(this, getString(R.string.font_applied, displayName), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w("termux", "Failed to write custom font.ttf", e)
            val message = resources.getString(R.string.writing_failed) + e.message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun copyColorFile(mCurrentSelectable: Selectable?) {
        if (mCurrentSelectable == null) return
        val outputFile = "colors.properties"
        try {
            val termuxDir = getTermuxDir()
            val destinationFile = File(termuxDir, outputFile).canonicalFile
            destinationFile.setWritable(true)
            destinationFile.parentFile?.setWritable(true)
            destinationFile.parentFile?.setExecutable(true)

            val defaultChoice = mCurrentSelectable.fileName == DEFAULT_FILENAME
            val atomicFile = AtomicFile(destinationFile)
            val out = atomicFile.startWrite()
            if (defaultChoice) {
                val comment = "# Using default color theme.".toByteArray(StandardCharsets.UTF_8)
                out.write(comment)
            } else {
                assets.open("colors/" + mCurrentSelectable.fileName).use {
                    it.copyTo(out)
                }
            }
            atomicFile.finishWrite(out)

            sendReloadBroadcast(true)
        } catch (e: Exception) {
            Log.w("termux", "Failed to write $outputFile", e)
            val message = resources.getString(R.string.writing_failed) + e.message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun sendReloadBroadcast(colors: Boolean) {
        val actionReload = "com.termux.app.reload_style"
        val executeIntent = Intent(actionReload)
        executeIntent.putExtra(actionReload, if (colors) "colors" else "font")
        sendBroadcast(executeIntent)
    }
}

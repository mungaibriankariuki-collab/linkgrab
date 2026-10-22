package com.linkgrab.app

import android.Manifest
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: TextInputEditText
    private lateinit var modeGroup: MaterialButtonToggleGroup
    private lateinit var downloadButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var updateButton: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var status: android.widget.TextView
    private lateinit var log: android.widget.TextView

    private var engineReady = false
    private var busy = false
    private var processId: String? = null
    private var cancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        modeGroup = findViewById(R.id.modeGroup)
        downloadButton = findViewById(R.id.downloadButton)
        cancelButton = findViewById(R.id.cancelButton)
        updateButton = findViewById(R.id.updateButton)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)

        findViewById<MaterialButton>(R.id.pasteButton).setOnClickListener { pasteFromClipboard() }
        downloadButton.setOnClickListener { startDownload() }
        cancelButton.setOnClickListener { cancelDownload() }
        updateButton.setOnClickListener { updateEngine(userAsked = true) }

        // A direct .apk link switches to APK mode by itself.
        urlInput.doAfterTextChanged { text ->
            val path = Uri.parse(text?.toString()?.trim() ?: "").path ?: ""
            if (path.endsWith(".apk", ignoreCase = true)) modeGroup.check(R.id.modeApk)
        }

        handleShareIntent(intent)
        initEngine()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        // Apps often share "Some title https://youtu.be/..." so pull out just the link.
        val link = Regex("""https?://\S+""").find(text)?.value ?: text
        urlInput.setText(link)
    }

    private fun pasteFromClipboard() {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            status.text = "Clipboard is empty. Copy a link first."
            return
        }
        val link = Regex("""https?://\S+""").find(text)?.value ?: text.trim()
        urlInput.setText(link)
        urlInput.setSelection(link.length)
    }

    // ---------- engine setup ----------

    private fun initEngine() {
        status.text = "Getting ready… (first launch takes a few seconds)"
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    YoutubeDL.getInstance().init(applicationContext)
                    FFmpeg.getInstance().init(applicationContext)
                    null
                } catch (e: Exception) {
                    e.message ?: e.toString()
                }
            }
            if (error != null) {
                status.text = "Couldn't start the downloader: $error"
                return@launch
            }
            engineReady = true
            downloadButton.isEnabled = true
            updateButton.isEnabled = true
            status.text = "Ready. Paste a link and tap Download."
            // Sites change often, so quietly fetch the newest yt-dlp on each launch.
            updateEngine(userAsked = false)
        }
    }

    private fun updateEngine(userAsked: Boolean) {
        if (busy) return
        updateButton.isEnabled = false
        if (userAsked) status.text = "Checking for a newer downloader engine…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(applicationContext)?.name
                } catch (e: Exception) {
                    "FAILED: ${e.message}"
                }
            }
            updateButton.isEnabled = true
            val version = YoutubeDL.getInstance().versionName(applicationContext) ?: "unknown"
            if (userAsked || result == "DONE") {
                status.text = when {
                    result == "DONE" -> "Downloader engine updated to $version."
                    result == "ALREADY_UP_TO_DATE" -> "Downloader engine is up to date ($version)."
                    else -> "Update didn't work. Check your internet. ($result)"
                }
            }
        }
    }

    // ---------- downloads ----------

    private fun startDownload() {
        val url = urlInput.text?.toString()?.trim().orEmpty()
        if (!URLUtil.isNetworkUrl(url)) {
            status.text = "That doesn't look like a link. It should start with http:// or https://"
            return
        }
        if (!engineReady || busy) return

        // Android 8 and 9 need permission to write to the Download folder.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            status.text = "Allow storage access, then tap Download again."
            return
        }

        when (modeGroup.checkedButtonId) {
            R.id.modeApk -> downloadDirectFile(url)
            R.id.modeSong -> downloadMedia(url, audioOnly = true)
            else -> downloadMedia(url, audioOnly = false)
        }
    }

    private fun downloadDirectFile(url: String) {
        val name = URLUtil.guessFileName(url, null, "application/vnd.android.package-archive")
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setMimeType(if (name.endsWith(".apk", true)) "application/vnd.android.package-archive" else null)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "LinkGrab/$name")
        try {
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            status.text = "Downloading $name. Check your notifications. Tap it when done to install."
            log.text = ""
        } catch (e: Exception) {
            status.text = "Couldn't start that download: ${e.message}"
        }
    }

    private fun downloadMedia(url: String, audioOnly: Boolean) {
        val workDir = File(getExternalFilesDir(null), "work-${System.currentTimeMillis()}").apply { mkdirs() }
        val request = YoutubeDLRequest(url).apply {
            addOption("-o", workDir.absolutePath + "/%(title).150B.%(ext)s")
            addOption("--no-playlist")
            addOption("--no-mtime")
            if (audioOnly) {
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "0")
                addOption("--embed-thumbnail")
                addOption("--embed-metadata")
            } else {
                addOption("-f", "bv*[ext=mp4][height<=1080]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b")
                addOption("--merge-output-format", "mp4")
            }
        }

        val id = UUID.randomUUID().toString()
        processId = id
        cancelled = false
        setBusy(true)
        status.text = if (audioOnly) "Downloading song…" else "Downloading video…"
        log.text = ""

        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    YoutubeDL.getInstance().execute(request, id) { pct, eta, line ->
                        runOnUiThread {
                            if (pct >= 0) {
                                progress.isIndeterminate = false
                                progress.setProgressCompat(pct.toInt(), true)
                                status.text = "${pct.toInt()}%" + if (eta > 0) " · about ${eta}s left" else ""
                            }
                            if (line.isNotBlank()) log.text = line
                        }
                    }
                    null
                } catch (e: Exception) {
                    e.message ?: e.toString()
                }
            }

            if (cancelled) {
                workDir.deleteRecursively()
                setBusy(false)
                status.text = "Download cancelled."
                return@launch
            }
            if (error != null) {
                workDir.deleteRecursively()
                setBusy(false)
                status.text = "Download failed. If this site worked before, tap \"Update downloader engine\" and try again."
                log.text = error.lines().lastOrNull { it.contains("ERROR") } ?: error.take(600)
                return@launch
            }

            status.text = "Saving…"
            val saved = withContext(Dispatchers.IO) {
                workDir.listFiles().orEmpty()
                    .filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                    .mapNotNull { f -> runCatching { saveToDownloads(f); f.name }.getOrNull() }
                    .also { workDir.deleteRecursively() }
            }
            setBusy(false)
            status.text = if (saved.isEmpty()) "Finished, but no file was produced." else "Saved to Download/LinkGrab ✓"
            log.text = saved.joinToString("\n")
        }
    }

    private fun saveToDownloads(file: File) {
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LinkGrab")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Couldn't create ${file.name}")
            contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LinkGrab")
            dir.mkdirs()
            file.copyTo(File(dir, file.name), overwrite = true)
        }
    }

    private fun cancelDownload() {
        val id = processId ?: return
        cancelled = true
        YoutubeDL.getInstance().destroyProcessById(id)
    }

    private fun setBusy(isBusy: Boolean) {
        busy = isBusy
        downloadButton.isEnabled = !isBusy && engineReady
        updateButton.isEnabled = !isBusy && engineReady
        cancelButton.visibility = if (isBusy) View.VISIBLE else View.GONE
        progress.visibility = if (isBusy) View.VISIBLE else View.INVISIBLE
        if (isBusy) {
            progress.isIndeterminate = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            processId = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

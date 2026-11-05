package ru.naladchik.notebook

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import java.io.File

/**
 * Блокнот наладчика — оболочка над страницей assets/index.html.
 * Данные и фото хранятся внутри приложения (IndexedDB WebView), интернет не нужен.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraFile: File? = null

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    /** Рукоятка Testo по Bluetooth: события уходят в страницу как window.__testo({...}). */
    private val testo by lazy {
        TestoBle(this) { o -> if (::web.isInitialized) web.evaluateJavascript("window.__testo&&window.__testo($o)", null) }
    }

    private fun btPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private val btPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res.values.all { it }) testo.start()
            else web.evaluateJavascript("window.__testo&&window.__testo({e:'state',s:'err',msg:'Без разрешения «Устройства поблизости» блокнот не найдёт рукоятку.'})", null)
        }

    private fun testoStart() {
        val need = btPerms().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) testo.start() else btPermLauncher.launch(need.toTypedArray())
    }

    /** Результат выбора фото / файла (галерея, камера, проводник). */
    private val pickLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cb = fileCallback
            fileCallback = null
            if (cb == null) return@registerForActivityResult
            val uris = mutableListOf<Uri>()
            if (res.resultCode == Activity.RESULT_OK) {
                val data: Intent? = res.data
                val clip = data?.clipData
                val single: Uri? = data?.data
                if (clip != null) {
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                } else if (single != null) {
                    uris.add(single)
                } else {
                    cameraFile?.let { f ->
                        if (f.exists() && f.length() > 0) {
                            uris.add(FileProvider.getUriForFile(this, "$packageName.files", f))
                        }
                    }
                }
            }
            cameraFile = null
            cb.onReceiveValue(uris.toTypedArray())
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            textZoom = 100
            mediaPlaybackRequiresUserGesture = true
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == APP_HOST) return false
                // внешние ссылки (ГОСТ, сайты) — во внешний браузер
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url)); true
                } catch (e: Exception) {
                    true
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val accept = params.acceptTypes.joinToString(",")
                val multiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                return try {
                    pickLauncher.launch(buildChooser(accept, multiple)); true
                } catch (e: Exception) {
                    fileCallback = null
                    callback.onReceiveValue(null)
                    Toast.makeText(this@MainActivity, "Не удалось открыть выбор файла", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.__vnbBack && window.__vnbBack()) ? 'yes' : 'no'") { r ->
                    if (r == null || !r.contains("yes")) finish()
                }
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        if (web.url == null) web.loadUrl(START_URL)
    }

    /** Датчик атмосферного давления телефона (есть не во всех телефонах), гПа. */
    @Volatile private var baroHpa = Float.NaN
    private val sensors by lazy { getSystemService(SENSOR_SERVICE) as? SensorManager }
    private val baroSensor by lazy { sensors?.getDefaultSensor(Sensor.TYPE_PRESSURE) }
    private val baroListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) { if (e.values.isNotEmpty()) baroHpa = e.values[0] }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    override fun onResume() {
        super.onResume()
        baroSensor?.let { try { sensors?.registerListener(baroListener, it, SensorManager.SENSOR_DELAY_NORMAL) } catch (_: Exception) {} }
    }

    override fun onPause() {
        try { sensors?.unregisterListener(baroListener) } catch (_: Exception) {}
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        try { testo.stop() } catch (_: Exception) {}
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        web.destroy()
        super.onDestroy()
    }

    /** Фото: камера + галерея; остальные файлы (резервная копия) — проводник. */
    private fun buildChooser(accept: String, multiple: Boolean): Intent {
        val isImage = accept.contains("image")
        val withPdf = accept.contains("pdf")
        val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            if (isImage && withPdf) {
                // схема: картинка или PDF
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
            } else {
                type = if (isImage) "image/*" else "*/*"
            }
            if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        if (!isImage) return Intent.createChooser(pick, "Выберите файл")

        val extras = mutableListOf<Intent>()
        val cam = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (cam.resolveActivity(packageManager) != null) {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            cameraFile = f
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            cam.putExtra(MediaStore.EXTRA_OUTPUT, uri)
            cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            extras.add(cam)
        }
        return Intent.createChooser(pick, getString(if (withPdf) R.string.pick_scheme else R.string.pick_photo)).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
        }
    }

    /** Мост для страницы: сохранить файл (таблица, сводка, копия) и предложить отправить. */
    inner class Bridge {
        /** «Сохранить»: только в «Загрузки / Блокнот наладчика»; отправка — отдельной кнопкой «Отправить…» (shareFile). */
        @JavascriptInterface
        fun saveFile(name: String, base64: String, mime: String) {
            val bytes = try {
                Base64.decode(base64, Base64.DEFAULT)
            } catch (e: Exception) {
                toast("Файл не сохранён: ошибка данных"); return
            }
            val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            runOnUiThread {
                if (saveToDownloads(safeName, bytes, mime)) {
                    toast("Сохранено: Загрузки/$FOLDER/$safeName")
                } else {
                    // старый Android (до 10): в «Загрузки» напрямую не записать — предлагаем отправить
                    shareFile(name, base64, mime)
                }
            }
        }

        /** «Отправить…»: только окно «Поделиться» (WhatsApp, Telegram, почта), без копии в Загрузках. */
        @JavascriptInterface
        fun shareFile(name: String, base64: String, mime: String) {
            val bytes = try {
                Base64.decode(base64, Base64.DEFAULT)
            } catch (e: Exception) {
                toast("Файл не отправлен: ошибка данных"); return
            }
            val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            runOnUiThread {
                try {
                    val shareFile = File(File(cacheDir, "exports").apply { mkdirs() }, safeName)
                    shareFile.writeBytes(bytes)
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", shareFile)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, safeName)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, getString(R.string.send_file)))
                } catch (e: Exception) {
                    toast("Не удалось отправить — нажмите «Сохранить»")
                }
            }
        }

        /** «Отправить замечания»: текст в мессенджер или почту. */
        @JavascriptInterface
        fun shareText(text: String) {
            runOnUiThread {
                try {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    startActivity(Intent.createChooser(send, getString(R.string.send_file)))
                } catch (e: Exception) {
                    toast("Не удалось отправить")
                }
            }
        }

        /** Тема страницы: строка состояния и панель навигации в цвет фона. */
        @JavascriptInterface
        fun setBars(dark: Boolean) {
            runOnUiThread { applyBars(dark) }
        }

        @JavascriptInterface
        fun isApp(): Boolean = true

        /** Рукоятка Testo: подключиться / отключиться / состояние. */
        @JavascriptInterface
        fun testoStart() { runOnUiThread { this@MainActivity.testoStart() } }

        @JavascriptInterface
        fun testoStop() { runOnUiThread { testo.stop() } }

        @JavascriptInterface
        fun testoState(): String = testo.state

        /** Давление с датчика телефона, гПа; пусто — датчика нет или ещё нет значения. */
        @JavascriptInterface
        fun baro(): String = if (baroHpa.isNaN() || baroHpa < 500f || baroHpa > 1200f) "" else String.format(java.util.Locale.US, "%.1f", baroHpa)

        /** Экран не гаснет, пока открыт «Замер». */
        @JavascriptInterface
        fun keepScreen(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun applyBars(dark: Boolean) {
        val bg = if (dark) 0xFF0E1317.toInt() else 0xFFE8EDF2.toInt()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        web.setBackgroundColor(bg)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    private fun saveToDownloads(name: String, bytes: ByteArray, mime: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val APP_HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$APP_HOST/assets/index.html"
        private const val FOLDER = "Блокнот наладчика"
    }
}

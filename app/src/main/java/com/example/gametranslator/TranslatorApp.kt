package com.example.gametranslator

import android.app.Activity
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS = "gt"
private const val GEMINI_MODEL = "gemini-2.5-flash"

class MainActivity : Activity() {
    private val prefs get() = getSharedPreferences(PREFS, MODE_PRIVATE)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p, p * 2, p, p) }
        fun add(v: View) = col.addView(v)
        fun btn(s: String, f: () -> Unit) = add(Button(this).apply { text = s; setOnClickListener { f() } })
        
        add(TextView(this).apply { text = "1) Activá el traductor en Accesibilidad.\n2) Tocá el botón '訳' en el juego para traducir y congelar el texto." })
        btn("Abrir Accesibilidad") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        
        val keyBox = EditText(this).apply {
            hint = "Clave de Gemini (ej. AQ...)"
            setSingleLine()
            setText(prefs.getString("gkey", ""))
        }
        add(keyBox)
        btn("Guardar clave") {
            prefs.edit().putString("gkey", keyBox.text.toString().trim()).apply()
            Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show()
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }
}

class TranslatorService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private lateinit var btn: TextView
    private lateinit var ov: FrameLayout
    private var show = false
    private var busy = false
    private val jp = Regex("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF]")
    private val ocr by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val tr by lazy {
        Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE).setTargetLanguage(TranslateLanguage.SPANISH).build())
    }

    override fun onServiceConnected() {
        val m = WindowManager.LayoutParams.MATCH_PARENT
        ov = FrameLayout(this).apply { visibility = View.GONE }
        wm.addView(ov, WindowManager.LayoutParams(m, m, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT))
        
        btn = TextView(this).apply { text = "訳"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
        updateButtonColor(false)
        
        val bp = WindowManager.LayoutParams(dp(52), dp(52), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply { x = dp(16); y = dp(120) }
        
        btn.setOnClickListener {
            show = !show
            updateButtonColor(show)
            ov.visibility = if (show) View.VISIBLE else View.GONE
            if (show) scan() else ov.removeAllViews()
        }
        wm.addView(btn, bp)
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
    }

    private fun updateButtonColor(active: Boolean) {
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) Color.argb(220, 30, 160, 60) else Color.argb(200, 40, 40, 40))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun scan() {
        if (busy) return
        busy = true
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hb = r.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hb.close()
                if (bmp == null) { busy = false; return }
                ocr.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { t ->
                        val s = t.textBlocks.joinToString(" ") { it.text }
                        if (s.isNotBlank() && jp.containsMatchIn(s)) translate(s) else busy = false
                    }
                    .addOnFailureListener { busy = false }
            }
            override fun onFailure(code: Int) { busy = false }
        })
    }

    private fun translate(text: String) {
        val key = prefs.getString("gkey", "").orEmpty()
        if (key.isBlank()) {
            tr.translate(text).addOnCompleteListener { task ->
                if (task.isSuccessful) render(task.result)
                busy = false
            }
        } else {
            Thread {
                try {
                    val prompt = "Sos traductor experto de videojuegos de Japón a español neutro. Traducí manteniendo el tono y sentido original. Devolvé ÚNICAMENTE el texto traducido sin explicaciones ni comillas extra: $text"
                    val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    val con = URL("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent").openConnection() as HttpURLConnection
                    con.requestMethod = "POST"; con.doOutput = true
                    con.setRequestProperty("Content-Type", "application/json")
                    con.setRequestProperty("Authorization", "Bearer $key")
                    con.outputStream.use { it.write(body.toString().toByteArray()) }
                    if (con.responseCode == 200) {
                        val resp = con.inputStream.bufferedReader().readText()
                        val resText = JSONObject(resp).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                        h.post { render(resText.trim()) }
                    } else {
                        fallbackOffline(text)
                    }
                } catch (e: Exception) {
                    fallbackOffline(text)
                }
                busy = false
            }.start()
        }
    }

    private fun fallbackOffline(text: String) {
        tr.translate(text).addOnCompleteListener { task ->
            if (task.isSuccessful) h.post { render(task.result) }
        }
    }

    private fun render(txt: String) {
        ov.removeAllViews()
        val v = TextView(this).apply {
            text = txt
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(230, 20, 20, 20))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            textSize = 18f
        }
        ov.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(24); topMargin = dp(180)
        })
    }
}

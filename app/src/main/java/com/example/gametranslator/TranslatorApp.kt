package com.example.gametranslator

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.app.Activity
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.util.TypedValue
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlin.math.*

private const val PREFS = "gt"
private const val DEFAULT_KEY = KeyEvent.KEYCODE_BUTTON_R2

class MainActivity : Activity() {
    private var waiting = false
    private lateinit var label: TextView
    private val prefs get() = getSharedPreferences(PREFS, MODE_PRIVATE)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p, p * 2, p, p) }
        fun add(v: View) = col.addView(v)
        fun btn(s: String, f: () -> Unit) = add(Button(this).apply { text = s; setOnClickListener { f() } })
        label = TextView(this)
        add(TextView(this).apply {
            text = "1) Activá 'Traductor de juegos' en Accesibilidad (Android 13+: Info de la app > ⋮ > Permitir ajustes restringidos).\n" +
                "2) Abrí el juego. Botón 訳: toque = traducir y fijar, doble toque = mostrar/ocultar, mantener = elegir zona, arrastrar = mover.\n"
        })
        add(label)
        btn("Abrir Accesibilidad") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btn("Cambiar botón del mando") {
            waiting = true
            prefs.edit().putBoolean("listening", true).apply()
            label.text = "Apretá el botón del mando…"
        }
        btn("Pantalla completa (borrar zona)") { prefs.edit().remove("zone").apply() }
        setContentView(ScrollView(this).apply { addView(col) })
        refresh()
    }

    override fun onKeyDown(k: Int, e: KeyEvent?): Boolean {
        if (waiting && k != KeyEvent.KEYCODE_BACK && k != KeyEvent.KEYCODE_HOME) {
            prefs.edit().putInt("pad_key", k).putBoolean("listening", false).apply()
            waiting = false; refresh(); return true
        }
        return super.onKeyDown(k, e)
    }

    override fun onPause() {
        prefs.edit().putBoolean("listening", false).apply(); waiting = false; super.onPause()
    }

    private fun refresh() {
        label.text = "Botón del mando: " + KeyEvent.keyCodeToString(prefs.getInt("pad_key", DEFAULT_KEY))
    }
}

class TranslatorService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private lateinit var btn: TextView
    private lateinit var bp: WindowManager.LayoutParams
    private lateinit var ov: FrameLayout
    private var sel: View? = null
    private var live = false; private var show = false; private var busy = false; private var ready = false
    private var last = ""
    private val jp = Regex("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF]")
    private val ocr by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val tr by lazy {
        Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE).setTargetLanguage(TranslateLanguage.SPANISH).build())
    }
    private val full = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun params(w: Int, hh: Int, f: Int) = WindowManager.LayoutParams(
        w, hh, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, f, PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    override fun onServiceConnected() {
        val m = WindowManager.LayoutParams.MATCH_PARENT
        ov = FrameLayout(this).apply { visibility = View.GONE }
        wm.addView(ov, params(m, m, full or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE))
        btn = TextView(this).apply { text = "訳"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
        paint()
        bp = params(dp(52), dp(52), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        ).apply { x = dp(16); y = dp(120) }
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { toggleLive(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { toggleShow(); return true }
            override fun onLongPress(e: MotionEvent) { pickZone() }
        })
        var sx = 0f; var sy = 0f; var bx = 0; var by = 0; var moved = false
        btn.setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_DOWN) { sx = e.rawX; sy = e.rawY; bx = bp.x; by = bp.y; moved = false }
            if (e.action == MotionEvent.ACTION_MOVE && (moved || abs(e.rawX - sx) > dp(8) || abs(e.rawY - sy) > dp(8))) {
                moved = true
                bp.x = bx + (e.rawX - sx).toInt(); bp.y = by + (e.rawY - sy).toInt()
                wm.updateViewLayout(btn, bp)
            }
            true
        }
        wm.addView(btn, bp)
        toast("Bajando modelo (solo la primera vez, con internet)")
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { ready = true; toast("Traductor listo") }
            .addOnFailureListener { toast("No se pudo bajar el modelo. Revisá internet.") }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(i: Intent?): Boolean {
        h.removeCallbacksAndMessages(null)
        runCatching { wm.removeView(btn) }; runCatching { wm.removeView(ov) }
        sel?.let { v -> runCatching { wm.removeView(v) } }
        return super.onUnbind(i)
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (prefs.getBoolean("listening", false)) return false
        if (e.keyCode != prefs.getInt("pad_key", DEFAULT_KEY)) return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) toggleLive()
        return true
    }

    private fun paint() {
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (live) Color.argb(220, 30, 140, 60) else Color.argb(200, 40, 40, 40))
        }
    }

    private fun toggleLive() {
        live = !live; paint()
        if (live) { 
            show = true; last = ""; ov.visibility = View.VISIBLE; scan()
            toast("Traducción activada (fija)") 
        } else { 
            show = false; ov.removeAllViews(); ov.visibility = View.GONE; 
            toast("Traducción apagada") 
        }
    }

    private fun toggleShow() {
        show = !show
        if (show) { last = ""; ov.visibility = View.VISIBLE; scan() } else ov.visibility = View.GONE
    }

    private fun scan() {
        if (busy) return
        if (!ready) { toast("El modelo todavía se está bajando…"); return }
        busy = true
        ov.visibility = View.INVISIBLE
        h.postDelayed({ capture() }, 120)
    }

    private fun capture() {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hb = r.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hb.close()
                if (bmp == null) done() else recognize(bmp)
            }
            override fun onFailure(code: Int) { done() }
        })
    }

    private fun zone(): Rect? {
        val p = prefs.getString("zone", null)?.split(",")?.mapNotNull { it.toIntOrNull() } ?: return null
        return if (p.size == 4) Rect(p[0], p[1], p[2], p[3]) else null
    }

    private fun recognize(src: Bitmap) {
        var bmp = src; var ox = 0; var oy = 0
        zone()?.let { z ->
            val l = z.left.coerceIn(0, src.width - 2); val t = z.top.coerceIn(0, src.height - 2)
            val r = z.right.coerceIn(l + 1, src.width); val b = z.bottom.coerceIn(t + 1, src.height)
            bmp = Bitmap.createBitmap(src, l, t, r - l, b - t); ox = l; oy = t
        }
        val dx = ox; val dy = oy
        ocr.process(InputImage.fromBitmap(bmp, 0)).addOnSuccessListener { onText(it, dx, dy) }.addOnFailureListener { done() }
    }

    private fun onText(t: Text, ox: Int, oy: Int) {
        val br = Rect(bp.x, bp.y, bp.x + bp.width, bp.y + bp.height)
        
        // Recogemos todos los bloques de texto válidos sin omitir ninguno y los ordenamos por posición vertical/horizontal
        val bl = t.textBlocks.mapNotNull { b ->
            val box = b.boundingBox ?: return@mapNotNull null
            val s = b.text.replace("\n", " ").trim()
            if (!jp.containsMatchIn(s)) return@mapNotNull null
            val r = Rect(box).apply { offset(ox, oy) }
            if (Rect.intersects(r, br)) null else r to s
        }.sortedWith(compareBy({ it.first.top }, { it.first.left }))

        if (bl.isEmpty()) { ov.removeAllViews(); last = ""; done(); return }
        
        val key = bl.joinToString("|") { it.second }
        if (key == last) { done(); return }
        
        val out = arrayOfNulls<String>(bl.size)
        var n = 0
        bl.forEachIndexed { i, x ->
            tr.translate(x.second).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    out[i] = task.result
                } else {
                    out[i] = x.second
                }
                n++
                if (n == bl.size) {
                    render(bl.map { it.first }, out)
                    last = key
                    done()
                }
            }
        }
    }

    private fun render(rs: List<Rect>, tx: Array<String?>) {
        ov.removeAllViews()
        val pad = dp(4)
        rs.forEachIndexed { i, r ->
            val s = tx.getOrNull(i) ?: return@forEachIndexed
            val v = TextView(this).apply {
                text = s
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(230, 20, 20, 20))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, pad, pad, pad)
                val targetSize = max(12f, r.height() * 0.65f)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, targetSize)
            }
            // Ancho automático (WRAP_CONTENT) para evitar barras gigantes y prevenir superposiciones entre cajas
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, r.height())
            lp.leftMargin = r.left
            lp.topMargin = r.top
            ov.addView(v, lp)
        }
    }

    private fun done() { busy = false; ov.visibility = if (show) View.VISIBLE else View.GONE }

    private fun pickZone() {
        if (sel != null) return
        toast("Dibujá un rectángulo sobre el texto. Un toque suelto = pantalla completa")
        val v = object : View(this@TranslatorService) {
            var x0 = 0f; var y0 = 0f; var x1 = 0f; var y1 = 0f; var on = false
            val line = Paint().apply { color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 4f }
            val fill = Paint().apply { color = Color.argb(60, 0, 200, 255) }
            override fun onDraw(c: Canvas) {
                c.drawColor(Color.argb(70, 0, 0, 0))
                if (on) { val r = RectF(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)); c.drawRect(r, fill); c.drawRect(r, line) }
            }
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; x1 = e.x; y1 = e.y; on = true }
                    MotionEvent.ACTION_MOVE -> { x1 = e.x; y1 = e.y }
                    MotionEvent.ACTION_UP -> {
                        val r = Rect(min(x0, x1).toInt(), min(y0, y1).toInt(), max(x0, x1).toInt(), max(y0, y1).toInt())
                        h.post { saveZone(r) }
                    }
                }
                invalidate(); return true
            }
        }
        sel = v
        val m = WindowManager.LayoutParams.MATCH_PARENT
        wm.addView(v, params(m, m, full))
    }

    private fun saveZone(r: Rect) {
        sel?.let { v -> runCatching { wm.removeView(v) } }; sel = null
        if (r.width() < dp(40) || r.height() < dp(30)) {
            prefs.edit().remove("zone").apply(); toast("Zona borrada: pantalla completa")
        } else {
            prefs.edit().putString("zone", "${r.left},${r.top},${r.right},${r.bottom}").apply(); toast("Zona guardada")
        }
        last = ""
    }
}

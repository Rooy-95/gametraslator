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
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS = "gt"
private const val DEFAULT_KEY = KeyEvent.KEYCODE_BUTTON_R2
private const val DEFAULT_MODEL = "gemini-3.1-flash-lite-preview"
private val MODELS = listOf("gemini-3.1-flash-lite-preview", "gemini-flash-lite-latest", "gemini-3.5-flash")

private class GemErr(val code: Int, msg: String) : Exception(msg)

// Un pedido a un modelo de Gemini. Devuelve el texto JSON de la respuesta.
private fun geminiText(key: String, model: String, prompt: String): String {
    val body = JSONObject()
        .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
    val con = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        .openConnection() as HttpURLConnection
    con.requestMethod = "POST"; con.connectTimeout = 6000; con.readTimeout = 20000; con.doOutput = true
    con.setRequestProperty("Content-Type", "application/json")
    con.setRequestProperty("x-goog-api-key", key)
    con.outputStream.use { it.write(body.toString().toByteArray()) }
    val code = con.responseCode
    if (code != 200) {
        val err = try { con.errorStream?.bufferedReader()?.readText().orEmpty() } catch (e: Exception) { "" }
        throw GemErr(code, "HTTP $code ($model) " + err.replace("\n", " ").take(150))
    }
    val resp = con.inputStream.bufferedReader().readText()
    val parts = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
        .getJSONObject("content").getJSONArray("parts")
    val sb = StringBuilder()
    for (i in 0 until parts.length()) {
        val p = parts.getJSONObject(i)
        if (!p.optBoolean("thought", false)) sb.append(p.optString("text", ""))
    }
    return sb.toString().trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}

// Prueba el modelo elegido y, si no existe o no hay cupo, los siguientes. Devuelve (modelo que funcionó, texto).
private fun geminiTry(key: String, pref: String, prompt: String): Pair<String, String> {
    var last: Exception? = null
    for (m in (listOf(pref) + MODELS).distinct()) {
        try {
            return Pair(m, geminiText(key, m, prompt))
        } catch (e: GemErr) {
            last = e
            if (e.code == 400 || e.code == 401 || e.code == 403) throw e   // clave mal: no sirve probar otros modelos
        }
    }
    throw last ?: Exception("sin respuesta")
}

// Gemini agrupa las líneas que forman una misma frase u opción y las traduce.
private fun geminiGroups(key: String, pref: String, lines: List<String>): Pair<String, List<Pair<List<Int>, String>>> {
    val numbered = lines.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
    val prompt = "Sos traductor de videojuegos del japonés al español neutro. Abajo están las líneas de texto que un OCR leyó en la pantalla " +
        "de una novela visual, numeradas. Una línea puede ser parte de una frase que sigue en la línea siguiente (aunque termine en puntos suspensivos), " +
        "o ser una frase u opción de respuesta independiente. Agrupá las líneas que forman una misma frase u opción y traducí cada grupo al español " +
        "de forma natural. El OCR puede equivocarse (por ejemplo, confundir 了 con 3): deducí el texto más probable. Respetá el tono, el género y la " +
        "persona (俺 y 僕 son un hablante masculino; 彼氏 o カレシ es novio, 彼女 o カノジョ es novia). Los nombres propios dejalos como están. " +
        "Devolvé SOLO un arreglo JSON de objetos con este formato: [{\"l\":[1,2],\"t\":\"traducción\"}], donde l son los números de línea del grupo, " +
        "en orden, y cada línea aparece en un solo grupo.\n\n$numbered"
    val (model, txt) = geminiTry(key, pref, prompt)
    val arr = JSONArray(txt)
    val groups = ArrayList<Pair<List<Int>, String>>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val la = o.optJSONArray("l") ?: continue
        val idx = (0 until la.length()).map { la.optInt(it) }.filter { it in 1..lines.size }
        val t = o.optString("t")
        if (idx.isNotEmpty() && t.isNotBlank()) groups.add(Pair(idx, t))
    }
    return Pair(model, groups)
}

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
                "2) Abrí el juego. Botón 訳: toque = traducción en vivo (verde), doble toque = congelar/seguir (azul), mantener = elegir zona, arrastrar = mover. Con la traducción en vivo apagada, el doble toque traduce una sola vez.\n" +
                "Mando: una vez = prender/apagar, dos veces = congelar/seguir.\n" +
                "Modo FIJO (botón de abajo): un toque traduce una sola vez y deja la traducción fija, sin parpadeo. Otro toque la quita. Ideal para novelas visuales.\n"
        })
        add(label)
        btn("Abrir Accesibilidad") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btn("Cambiar botón del mando") {
            waiting = true
            prefs.edit().putBoolean("listening", true).apply()
            label.text = "Apretá el botón del mando…"
        }
        btn("Pantalla completa (borrar zona)") { prefs.edit().remove("zone").apply() }
        val vel = Button(this)
        fun showVel() { vel.text = "Velocidad de revisión: " + (prefs.getLong("interval", 2000L) / 1000) + " s (tocá para cambiar)" }
        vel.setOnClickListener {
            val next = when (prefs.getLong("interval", 2000L)) { 2000L -> 3000L; 3000L -> 4000L; else -> 2000L }
            prefs.edit().putLong("interval", next).apply(); showVel()
        }
        showVel(); add(vel)
        val mode = Button(this)
        fun showMode() { mode.text = if (prefs.getBoolean("fixed", false)) "Modo: FIJO (un toque traduce y deja la traducción fija)" else "Modo: EN VIVO (revisa solo cada tanto)" }
        mode.setOnClickListener { prefs.edit().putBoolean("fixed", !prefs.getBoolean("fixed", false)).apply(); showMode() }
        showMode(); add(mode)

        val keyBox = EditText(this).apply {
            hint = "Clave de Gemini (opcional)"
            setSingleLine()
            setText(prefs.getString("gkey", ""))
        }
        val modelBox = EditText(this).apply {
            hint = "Modelo de Gemini"
            setSingleLine()
            setText(prefs.getString("gmodel", DEFAULT_MODEL))
        }
        val status = TextView(this)
        add(keyBox); add(modelBox)
        btn("Guardar clave y modelo") {
            prefs.edit().putString("gkey", keyBox.text.toString().trim())
                .putString("gmodel", modelBox.text.toString().trim().ifEmpty { DEFAULT_MODEL }).apply()
            status.text = "Guardado"
        }
        btn("Probar Gemini") {
            val k = keyBox.text.toString().trim()
            val mdl = modelBox.text.toString().trim().ifEmpty { DEFAULT_MODEL }
            status.text = "Probando…"
            Thread {
                var ok: String? = null
                val msg = try {
                    val (m, g) = geminiGroups(k, mdl, listOf("了解！", "俺はキミのカレシじゃないから"))
                    ok = m
                    "Gemini OK ($m): " + g.joinToString(" | ") { it.second }
                } catch (e: Exception) { "Falló: " + e.message }
                runOnUiThread {
                    status.text = msg
                    ok?.let { m -> modelBox.setText(m); prefs.edit().putString("gkey", k).putString("gmodel", m).apply() }
                }
            }.start()
        }
        add(status)
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
    private var frozen = false; private var warned = false
    private var keyPending: Runnable? = null
    private var last = ""
    private val pageCache = HashMap<String, List<Pair<List<Int>, String>>>()
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
            override fun onDoubleTap(e: MotionEvent): Boolean { doubleTap(); return true }
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

    // Mando: una vez = prender/apagar, dos veces rápido = congelar/seguir
    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (prefs.getBoolean("listening", false)) return false
        if (e.keyCode != prefs.getInt("pad_key", DEFAULT_KEY)) return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            val pending = keyPending
            if (pending != null) {
                h.removeCallbacks(pending); keyPending = null; doubleTap()
            } else {
                val r = Runnable { keyPending = null; toggleLive() }
                keyPending = r; h.postDelayed(r, 300)
            }
        }
        return true
    }

    private fun paint() {
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(when {
                frozen -> Color.argb(220, 30, 90, 170)
                live || show -> Color.argb(220, 30, 140, 60)
                else -> Color.argb(200, 40, 40, 40)
            })
        }
    }

    private fun toggleLive() {
        if (prefs.getBoolean("fixed", false)) { fixedTap(); return }
        live = !live; frozen = false; warned = false
        if (live) {
            show = true; last = ""; ov.removeAllViews(); ov.visibility = View.VISIBLE
            h.post(tick); toast("Traducción en vivo: ACTIVADA")
        } else {
            h.removeCallbacks(tick); show = false; ov.removeAllViews(); ov.visibility = View.GONE
            toast("Traducción en vivo: apagada")
        }
        paint()
    }

    // Modo fijo: traduce una sola vez, deja la traducción fija y no revisa más hasta el próximo toque
    private fun fixedTap() {
        live = false; frozen = false; warned = false; h.removeCallbacks(tick)
        show = !show
        if (show) { last = ""; ov.removeAllViews(); ov.visibility = View.VISIBLE; scan() }
        else { ov.removeAllViews(); ov.visibility = View.GONE }
        paint()
    }

    private fun doubleTap() {
        if (live) {
            frozen = !frozen; paint()
            toast(if (frozen) "Traducción congelada (doble toque para seguir)" else "Traducción en vivo: sigue")
        } else {
            show = !show
            if (show) { last = ""; ov.visibility = View.VISIBLE; scan() } else ov.visibility = View.GONE
            paint()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!live) return
            if (show && !frozen) scan()
            h.postDelayed(this, prefs.getLong("interval", 2000L))
        }
    }

    private fun showOv() { ov.visibility = if (show) View.VISIBLE else View.GONE }

    private fun scan() {
        if (busy) return
        if (!ready && prefs.getString("gkey", "").isNullOrBlank()) { toast("El modelo todavía se está bajando…"); return }
        busy = true
        ov.visibility = View.INVISIBLE   // así el OCR no lee la traducción
        h.postDelayed({ capture() }, 100)
    }

    private fun capture() {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hb = r.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hb.close()
                showOv()   // la traducción vuelve enseguida: menos parpadeo
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
        val ls = ArrayList<Pair<Rect, String>>()
        for (b in t.textBlocks) for (ln in b.lines) {
            val lb = ln.boundingBox ?: continue
            val s = ln.text.trim()
            if (!jp.containsMatchIn(s)) continue
            val r = Rect(lb).apply { offset(ox, oy) }
            if (!Rect.intersects(r, br)) ls.add(Pair(r, s))
        }
        ls.sortWith(compareBy<Pair<Rect, String>>({ it.first.top }, { it.first.left }))
        if (ls.isEmpty()) { ov.removeAllViews(); last = ""; done(); return }
        val key = ls.joinToString("|") { it.second }
        if (key == last) { done(); return }
        val cached = pageCache[key]
        if (cached != null) { draw(ls, cached); last = key; done(); return }

        val gk = prefs.getString("gkey", "").orEmpty().trim()
        if (gk.isEmpty()) { offlineGroups(ls, key); return }
        val pref = prefs.getString("gmodel", "").orEmpty().trim().ifEmpty { DEFAULT_MODEL }
        Thread {
            var err = ""
            var res: List<Pair<List<Int>, String>>? = null
            try {
                val (m, g) = geminiGroups(gk, pref, ls.map { it.second })
                if (m != pref) prefs.edit().putString("gmodel", m).apply()
                res = g
            } catch (e: Exception) { err = e.message ?: "error" }
            val r = res
            h.post {
                if (r != null && r.isNotEmpty()) {
                    if (pageCache.size > 100) pageCache.clear()
                    pageCache[key] = r
                    draw(ls, r); last = key; done()
                } else {
                    if (!warned) {
                        warned = true
                        Toast.makeText(this, "Gemini falló: " + err.ifEmpty { "respuesta vacía" } + ". Uso el traductor sin internet.", Toast.LENGTH_LONG).show()
                    }
                    offlineGroups(ls, key)
                }
            }
        }.start()
    }

    // Sin Gemini: junta las líneas hasta un punto, signo o comillas (los puntos suspensivos no separan)
    private fun offlineGroups(ls: List<Pair<Rect, String>>, key: String) {
        val ends = "。！？!?』」）)"
        val groups = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        ls.forEachIndexed { i, l ->
            cur.add(i + 1)
            if (ends.indexOf(l.second.last()) >= 0) { groups.add(cur); cur = ArrayList() }
        }
        if (cur.isNotEmpty()) groups.add(cur)
        val out = arrayOfNulls<String>(groups.size); var n = 0
        groups.forEachIndexed { gi, g ->
            val text = g.joinToString("") { ls[it - 1].second }
            tr.translate(text).addOnCompleteListener { task ->
                if (task.isSuccessful) out[gi] = task.result
                if (++n == groups.size) {
                    val res = groups.indices.mapNotNull { i -> out[i]?.let { Pair(groups[i], it) } }
                    draw(ls, res); last = key; done()
                }
            }
        }
    }

    private fun draw(ls: List<Pair<Rect, String>>, groups: List<Pair<List<Int>, String>>) {
        ov.removeAllViews()
        val pad = dp(4)
        val sw = resources.displayMetrics.widthPixels
        for ((idx, text) in groups) {
            val rs = idx.filter { it in 1..ls.size }.map { ls[it - 1].first }
            if (rs.isEmpty()) continue
            val u = Rect(rs[0])
            for (r in rs.drop(1)) u.union(r)
            val size = ((u.height().toFloat() / rs.size) * 0.5f).coerceIn(dp(11).toFloat(), dp(30).toFloat())
            val v = TextView(this).apply {
                this.text = text; setTextColor(Color.WHITE); setBackgroundColor(Color.argb(230, 20, 20, 20))
                setPadding(pad, pad, pad, pad)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            }
            val left = max(0, u.left - pad)
            val w = max(dp(60), min(max((u.width() * 1.5f).toInt() + pad * 2, dp(160)), sw - left))
            val lp = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = left; lp.topMargin = max(0, u.top - pad)
            ov.addView(v, lp)
        }
    }

    private fun done() { busy = false; showOv() }

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

package com.imhuisu.mytool.sched

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * 웹 일정(sched/index.html)과 같은 규칙으로 동작한다. 바꿀 땐 양쪽을 맞출 것.
 *  - id  = SHA-256("mytool-sched-id:" + 비밀번호) 의 hex
 *  - 키  = PBKDF2-HMAC-SHA256(비밀번호, "mytool-sched-key-v1", 310000회, 256bit)
 *  - 서버 data = base64(iv 12바이트 + AES-GCM 암호문·태그)
 *  - 문서 = { ev:{id:일정}, tk:{id:할 일} }, 항목의 del 은 삭제 표시
 */
object Sched {
    const val BASE = "https://mytool-3up.pages.dev/"
    const val WEB = BASE + "sched/"
    private const val PREFS = "sched"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isLinked(c: Context) = prefs(c).getString("id", null) != null
    fun lastSync(c: Context) = prefs(c).getLong("at", 0L)
    fun lastError(c: Context) = prefs(c).getString("err", "") ?: ""
    fun cachedDoc(c: Context): JSONObject? = prefs(c).getString("doc", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun unlink(c: Context) { prefs(c).edit().clear().apply() }

    /* ---------- 암호 ---------- */
    private fun sha256Hex(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /* 웹 crypto.subtle 의 PBKDF2 와 똑같이 (비밀번호를 UTF-8 바이트로) */
    private fun pbkdf2(pass: String, salt: ByteArray, iter: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pass.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(salt); mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val t = u.copyOf()
        for (i in 1 until iter) {
            u = mac.doFinal(u)
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        return t
    }

    /** 비밀번호로 연결. 느린 작업(PBKDF2)이라 백그라운드에서 부를 것 */
    fun link(c: Context, pass: String) {
        val id = sha256Hex("mytool-sched-id:$pass")
        val key = pbkdf2(pass, "mytool-sched-key-v1".toByteArray(Charsets.UTF_8), 310000)
        prefs(c).edit().putString("id", id).putString("key", Base64.encodeToString(key, Base64.NO_WRAP)).apply()
    }

    private fun decrypt(c: Context, data: String): JSONObject {
        val key = Base64.decode(prefs(c).getString("key", ""), Base64.NO_WRAP)
        val all = Base64.decode(data, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, all, 0, 12))
        return JSONObject(String(cipher.doFinal(all, 12, all.size - 12), Charsets.UTF_8))
    }

    /** 서버에서 받아와 저장. 실패하면 false (저장된 것은 그대로).
     *  requireData: 처음 연결할 때 true → 그 비밀번호로 저장된 일정이 없으면 실패 (오타 방지) */
    fun fetch(c: Context, requireData: Boolean = false): Boolean {
        val id = prefs(c).getString("id", null) ?: return false
        return try {
            val con = URL("${BASE}api/sched?id=$id").openConnection() as HttpURLConnection
            con.connectTimeout = 10000; con.readTimeout = 15000; con.useCaches = false
            val body = con.inputStream.bufferedReader().use { it.readText() }
            if (con.responseCode != 200) throw Exception("서버 ${con.responseCode}")
            val j = JSONObject(body)
            if (j.isNull("data") && requireData) { prefs(c).edit().putString("err", "이 비밀번호로 저장된 일정이 없어요").apply(); return false }
            val doc = if (j.isNull("data")) JSONObject().put("ev", JSONObject()).put("tk", JSONObject())
                      else decrypt(c, j.getString("data"))
            prefs(c).edit().putString("doc", doc.toString()).putLong("at", System.currentTimeMillis()).putString("err", "").apply()
            true
        } catch (e: javax.crypto.AEADBadTagException) {
            prefs(c).edit().putString("err", "비밀번호가 맞지 않아요").apply(); false
        } catch (e: Exception) {
            prefs(c).edit().putString("err", "연결 실패: ${e.message ?: e.javaClass.simpleName}").apply(); false
        }
    }

    /** 새 버전 확인: 사이트의 app/version.json */
    fun latestVersion(): Pair<Int, String>? = runCatching {
        val con = URL("${BASE}app/version.json").openConnection() as HttpURLConnection
        con.connectTimeout = 8000; con.useCaches = false
        val j = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
        j.getInt("versionCode") to (BASE + "app/")
    }.getOrNull()

    /* ---------- 위젯에 보일 줄 계산 (웹 renderMini 와 같은 규칙) ---------- */
    sealed class Row {
        data class Head(val text: String) : Row()
        data class Ev(val time: String, val title: String, val cat: Int, val state: Int) : Row()   // state 0 보통, 1 지금, 2 지남
        data class Tk(val title: String, val star: Boolean, val due: String, val dueKind: Int) : Row()  // 0 보통, 1 오늘, 2 지남
        data class Empty(val text: String) : Row()
    }

    private val F: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private const val WD = "일월화수목금토"
    fun dayLabel(d: LocalDate) = "${d.monthValue}월 ${d.dayOfMonth}일 (${WD[d.dayOfWeek.value % 7]})"

    private fun items(doc: JSONObject?, coll: String): List<JSONObject> {
        val o = doc?.optJSONObject(coll) ?: return emptyList()
        return o.keys().asSequence().mapNotNull { o.optJSONObject(it) }.filter { !it.has("del") || it.opt("del") == 0 || it.opt("del") == false }.toList()
    }

    private fun occurs(e: JSONObject, d: LocalDate): Boolean {
        val start = runCatching { LocalDate.parse(e.optString("d"), F) }.getOrNull() ?: return false
        if (d.isBefore(start)) return false
        return when (e.optString("r")) {
            "" -> { val end = e.optString("d2").takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it, F) }.getOrNull() } ?: start; !d.isAfter(end) }
            "d" -> true
            "w" -> start.dayOfWeek == d.dayOfWeek
            "m" -> start.dayOfMonth == d.dayOfMonth
            "y" -> start.dayOfMonth == d.dayOfMonth && start.monthValue == d.monthValue
            else -> false
        }
    }

    fun eventsOn(doc: JSONObject?, d: LocalDate) = items(doc, "ev").filter { occurs(it, d) }
        .sortedWith(compareBy<JSONObject>({ if (it.optString("s").isEmpty()) 0 else 1 }, { it.optString("s") }, { it.optString("t") }))

    fun openTasks(doc: JSONObject?, today: LocalDate): List<JSONObject> {
        val sorted = items(doc, "tk").filter { !it.optBoolean("done") }
            .sortedWith(compareBy<JSONObject>({ if (it.optBoolean("star")) 0 else 1 }, { it.optString("due").ifEmpty { "9999" } }, { it.optLong("u0") }))
        val t = today.format(F)
        val now = sorted.filter { val d = it.optString("due"); d.isNotEmpty() && d <= t }
        return now + sorted.filter { it !in now }
    }

    fun rows(doc: JSONObject?): List<Row> {
        val today = LocalDate.now(); val now = LocalTime.now().toString().substring(0, 5)
        val out = mutableListOf<Row>()
        fun ev(e: JSONObject, isToday: Boolean): Row.Ev {
            val s = e.optString("s"); val en = e.optString("e").ifEmpty { s }
            val state = if (!isToday || s.isEmpty()) 0 else if (now in s..en) 1 else if (now > en) 2 else 0
            return Row.Ev(if (s.isEmpty()) "종일" else s, e.optString("t"), e.optInt("c", 0), state)
        }
        val evs = eventsOn(doc, today)
        out += Row.Head("오늘 일정 ${evs.size}")
        if (evs.isEmpty()) out += Row.Empty("오늘 일정이 없어요") else evs.forEach { out += ev(it, true) }
        val tmr = eventsOn(doc, today.plusDays(1))
        if (tmr.isNotEmpty()) { out += Row.Head("내일"); tmr.take(3).forEach { out += ev(it, false) } }
        val tasks = openTasks(doc, today)
        out += Row.Head("할 일 ${tasks.size}")
        if (tasks.isEmpty()) out += Row.Empty("할 일이 없어요")
        tasks.take(15).forEach {
            val due = it.optString("due")
            var label = ""; var kind = 0
            if (due.isNotEmpty()) runCatching {
                val diff = ChronoUnit.DAYS.between(today, LocalDate.parse(due, F)).toInt()
                label = when { diff == 0 -> "오늘"; diff == 1 -> "내일"; diff == -1 -> "어제"; diff < 0 -> "${-diff}일 지남"; diff < 7 -> "${diff}일 뒤"; else -> dayLabel(LocalDate.parse(due, F)) }
                kind = if (diff < 0) 2 else if (diff == 0) 1 else 0
            }
            out += Row.Tk(it.optString("t"), it.optBoolean("star"), label, kind)
        }
        if (tasks.size > 15) out += Row.Empty("외 ${tasks.size - 15}개")
        return out
    }
}

package se.oeeapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

/** En stoppkategori. Planerade stopp (t.ex. rast) räknas bort från planerad produktionstid. */
data class Category(val id: String, var name: String, var planned: Boolean)

data class Reason(val id: String, var name: String, var categoryId: String)

/** source = "sensor" eller "manuell". end = null betyder att stoppet pågår. */
data class Stop(
    val id: String,
    var start: Long,
    var end: Long?,
    var reasonId: String?,
    val source: String,
)

data class Shift(
    val start: Long,
    var end: Long?,
    var idealCycleSec: Double,
    var good: Int,
    var scrap: Int,
    val stops: MutableList<Stop>,
) {
    val openStop: Stop? get() = stops.lastOrNull { it.end == null }
}

data class OeeResult(
    val totalMs: Long,
    val plannedStopMs: Long,
    val unplannedStopMs: Long,
    val plannedProductionMs: Long,
    val runMs: Long,
    val availability: Double,
    val performance: Double,
    val quality: Double,
) {
    val oee: Double get() = availability * performance * quality
}

/** Sparar allt lokalt i telefonen som JSON. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("oee", Context.MODE_PRIVATE)

    val categories = mutableListOf<Category>()
    val reasons = mutableListOf<Reason>()
    var shift: Shift? = null

    /** "none", "phone" eller "ble" */
    var sensorType: String
        get() = prefs.getString("sensorType", "none") ?: "none"
        set(v) = prefs.edit().putString("sensorType", v).apply()

    /** Sekunder utan rörelse innan ett stopp registreras. */
    var thresholdSec: Int
        get() = prefs.getInt("thresholdSec", 30)
        set(v) = prefs.edit().putInt("thresholdSec", v).apply()

    /** Hur stor acceleration (m/s²) som räknas som rörelse för telefonens sensor. */
    var phoneSensitivity: Float
        get() = prefs.getFloat("phoneSensitivity", 0.6f)
        set(v) = prefs.edit().putFloat("phoneSensitivity", v).apply()

    var bleAddress: String?
        get() = prefs.getString("bleAddress", null)
        set(v) = prefs.edit().putString("bleAddress", v).apply()

    var bleName: String?
        get() = prefs.getString("bleName", null)
        set(v) = prefs.edit().putString("bleName", v).apply()

    var lastIdealCycleSec: Double
        get() = prefs.getFloat("lastIdealCycle", 30f).toDouble()
        set(v) = prefs.edit().putFloat("lastIdealCycle", v.toFloat()).apply()

    fun load() {
        categories.clear()
        reasons.clear()
        val catJson = prefs.getString("categories", null)
        if (catJson == null) {
            seedDefaults()
        } else {
            val ca = JSONArray(catJson)
            for (i in 0 until ca.length()) {
                val o = ca.getJSONObject(i)
                categories.add(Category(o.getString("id"), o.getString("name"), o.getBoolean("planned")))
            }
            val ra = JSONArray(prefs.getString("reasons", "[]"))
            for (i in 0 until ra.length()) {
                val o = ra.getJSONObject(i)
                reasons.add(Reason(o.getString("id"), o.getString("name"), o.getString("categoryId")))
            }
        }
        shift = prefs.getString("shift", null)?.let { shiftFromJson(JSONObject(it)) }
    }

    fun save() {
        val ca = JSONArray()
        categories.forEach {
            ca.put(JSONObject().put("id", it.id).put("name", it.name).put("planned", it.planned))
        }
        val ra = JSONArray()
        reasons.forEach {
            ra.put(JSONObject().put("id", it.id).put("name", it.name).put("categoryId", it.categoryId))
        }
        val e = prefs.edit()
            .putString("categories", ca.toString())
            .putString("reasons", ra.toString())
        val s = shift
        if (s == null) e.remove("shift") else e.putString("shift", shiftToJson(s).toString())
        e.apply()
    }

    private fun seedDefaults() {
        fun cat(name: String, planned: Boolean, vararg rs: String) {
            val c = Category(newId(), name, planned)
            categories.add(c)
            rs.forEach { reasons.add(Reason(newId(), it, c.id)) }
        }
        cat("Planerat stopp", true, "Rast", "Planerat underhåll", "Möte")
        cat("Haveri", false, "Robotfel", "Mekaniskt fel", "Elfel", "Givarfel")
        cat("Ställ & omställning", false, "Produktbyte", "Verktygsbyte")
        cat("Materialbrist", false, "Material saknas", "Fullt utlopp")
        cat("Kvalitet", false, "Kontroll", "Justering")
        cat("Övrigt", false, "Okänd orsak")
        save()
    }

    fun reason(id: String?): Reason? = reasons.firstOrNull { it.id == id }
    fun category(id: String?): Category? = categories.firstOrNull { it.id == id }

    fun reasonLabel(id: String?): String {
        val r = reason(id) ?: return "Ej klassad"
        val c = category(r.categoryId)
        return if (c != null) "${c.name} – ${r.name}" else r.name
    }

    fun isPlanned(stop: Stop): Boolean = category(reason(stop.reasonId)?.categoryId)?.planned == true

    fun calc(s: Shift, now: Long): OeeResult {
        val end = s.end ?: now
        var plannedStop = 0L
        var unplannedStop = 0L
        for (st in s.stops) {
            val stEnd = st.end ?: end
            val d = max(0L, min(stEnd, end) - max(st.start, s.start))
            if (isPlanned(st)) plannedStop += d else unplannedStop += d
        }
        val total = max(0L, end - s.start)
        val plannedProd = max(0L, total - plannedStop)
        val run = max(0L, plannedProd - unplannedStop)
        val count = s.good + s.scrap
        val a = if (plannedProd > 0) run.toDouble() / plannedProd else 0.0
        val p = if (run > 0) s.idealCycleSec * 1000.0 * count / run else 0.0
        val q = if (count > 0) s.good.toDouble() / count else 0.0
        return OeeResult(total, plannedStop, unplannedStop, plannedProd, run, a, p, q)
    }

    private fun shiftToJson(s: Shift): JSONObject {
        val stops = JSONArray()
        s.stops.forEach {
            stops.put(
                JSONObject()
                    .put("id", it.id)
                    .put("start", it.start)
                    .put("end", it.end ?: JSONObject.NULL)
                    .put("reasonId", it.reasonId ?: JSONObject.NULL)
                    .put("source", it.source)
            )
        }
        return JSONObject()
            .put("start", s.start)
            .put("end", s.end ?: JSONObject.NULL)
            .put("ideal", s.idealCycleSec)
            .put("good", s.good)
            .put("scrap", s.scrap)
            .put("stops", stops)
    }

    private fun shiftFromJson(o: JSONObject): Shift {
        val stops = mutableListOf<Stop>()
        val a = o.getJSONArray("stops")
        for (i in 0 until a.length()) {
            val so = a.getJSONObject(i)
            stops.add(
                Stop(
                    so.getString("id"),
                    so.getLong("start"),
                    if (so.isNull("end")) null else so.getLong("end"),
                    if (so.isNull("reasonId")) null else so.getString("reasonId"),
                    so.getString("source"),
                )
            )
        }
        return Shift(
            o.getLong("start"),
            if (o.isNull("end")) null else o.getLong("end"),
            o.getDouble("ideal"),
            o.getInt("good"),
            o.getInt("scrap"),
            stops,
        )
    }
}

package se.oeeapp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var store: Store
    private val handler = Handler(Looper.getMainLooper())
    private val sv = Locale("sv", "SE")
    private val hhmm = SimpleDateFormat("HH:mm", sv)

    private var source: MotionSource? = null
    private var sensorStatus = "Ingen sensor vald"
    private var lastMotion = 0L
    private var tab = 0

    private lateinit var content: LinearLayout

    // Vyer på översikten som uppdateras varje sekund
    private var ovStatus: TextView? = null
    private var ovOee: TextView? = null
    private var ovA: TextView? = null
    private var ovP: TextView? = null
    private var ovQ: TextView? = null
    private var ovTimes: TextView? = null
    private var ovGood: TextView? = null
    private var ovScrap: TextView? = null
    private var ovStopBtn: MaterialButton? = null
    private var ovSensor: TextView? = null

    private val green = Color.parseColor("#16A34A")
    private val amber = Color.parseColor("#D97706")
    private val red = Color.parseColor("#DC2626")
    private val grey = Color.parseColor("#6B7280")

    private val ticker = object : Runnable {
        override fun run() {
            checkIdle()
            updateOverview()
            handler.postDelayed(this, 1000)
        }
    }

    // ---------------------------------------------------------------- livscykel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        store.load()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabs = TabLayout(this)
        listOf("Översikt", "Stopp", "Inställningar").forEach { tabs.addTab(tabs.newTab().setText(it)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                tab = t.position
                render()
            }

            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })
        root.addView(tabs)

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 8.dp(), 12.dp(), 24.dp())
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        render()
    }

    override fun onStart() {
        super.onStart()
        startSource()
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        stopSource()
        store.save()
    }

    // ---------------------------------------------------------------- sensorer

    private fun startSource() {
        stopSource()
        lastMotion = System.currentTimeMillis()
        source = when (store.sensorType) {
            "phone" -> PhoneMotionSource(this, { store.phoneSensitivity }, ::onMotion, ::onSensorStatus)
            "ble" -> {
                val addr = store.bleAddress
                when {
                    addr == null -> {
                        onSensorStatus("Ingen Bluetooth-enhet vald")
                        null
                    }
                    !hasBlePermissions() -> {
                        onSensorStatus("Behörighet saknas – välj enhet under Inställningar")
                        null
                    }
                    else -> BleMotionSource(this, addr, ::onMotion, ::onSensorStatus)
                }
            }
            else -> {
                onSensorStatus("Ingen sensor vald")
                null
            }
        }
        source?.start()
    }

    private fun stopSource() {
        source?.stop()
        source = null
    }

    private fun onSensorStatus(s: String) {
        sensorStatus = s
        updateOverview()
    }

    /** Anropas när sensorn känner rörelse. Avslutar ett pågående sensorstopp. */
    private fun onMotion() {
        val now = System.currentTimeMillis()
        lastMotion = now
        val s = store.shift ?: return
        if (s.end != null) return
        val open = s.openStop ?: return
        if (open.source == "sensor") {
            open.end = now
            store.save()
            vibrate()
            updateOverview()
            askReason(open)
        }
    }

    /** Startar ett stopp om ingen rörelse har setts på inställd tid. */
    private fun checkIdle() {
        val s = store.shift ?: return
        if (s.end != null || source == null || s.openStop != null) return
        val now = System.currentTimeMillis()
        val start = maxOf(lastMotion, s.start)
        if (now - start >= store.thresholdSec * 1000L) {
            s.stops.add(Stop(newId(), start, null, null, "sensor"))
            store.save()
            vibrate()
            if (tab == 1) render()
        }
    }

    // ---------------------------------------------------------------- vyer

    private fun render() {
        ovStatus = null; ovOee = null; ovA = null; ovP = null; ovQ = null
        ovTimes = null; ovGood = null; ovScrap = null; ovStopBtn = null; ovSensor = null
        content.removeAllViews()
        when (tab) {
            0 -> renderOverview()
            1 -> renderStops()
            else -> renderSettings()
        }
        updateOverview()
        val active = store.shift?.let { it.end == null } == true
        if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun renderOverview() {
        val s = store.shift
        if (s == null || s.end != null) {
            val c = card()
            c.addView(tv(if (s == null) "Inget skift pågår" else "Senaste skiftet", 20f, bold = true))
            if (s != null) {
                val r = store.calc(s, System.currentTimeMillis())
                c.addView(tv("${hhmm.format(Date(s.start))}–${hhmm.format(Date(s.end!!))}", 14f, color = grey))
                c.addView(tv("OEE ${pct(r.oee)}", 40f, bold = true, color = oeeColor(r.oee)))
                c.addView(tv("Tillgänglighet ${pct(r.availability)} · Prestanda ${pct(r.performance)} · Kvalitet ${pct(r.quality)}"))
                c.addView(tv("Bra ${s.good} · Kass ${s.scrap} · ${s.stops.size} stopp", 14f, color = grey))
                c.addView(spacer())
                c.addView(btn("Dela resultat", outlined = true) { shareCsv(s) })
            }
            c.addView(btn("Starta nytt skift") { startShift() })
            return
        }

        // Status
        ovStatus = tv("", 22f, bold = true, color = Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(12.dp(), 14.dp(), 12.dp(), 14.dp())
        }
        content.addView(ovStatus, matchWrap().apply { bottomMargin = 8.dp() })

        // OEE
        val c1 = card()
        c1.addView(tv("OEE", 14f, color = grey))
        ovOee = tv("", 48f, bold = true)
        c1.addView(ovOee)
        val row = hRow()
        ovA = tv("", 14f); ovP = tv("", 14f); ovQ = tv("", 14f)
        row.addW(ovA!!); row.addW(ovP!!); row.addW(ovQ!!)
        c1.addView(row)

        // Räknare
        val c2 = card()
        c2.addView(tv("Producerat", 14f, color = grey))
        ovGood = tv("", 20f, bold = true)
        val r1 = hRow()
        r1.addW(ovGood!!, 2f)
        r1.addW(btn("−1", outlined = true) { if (s.good > 0) s.good--; store.save(); updateOverview() })
        r1.addW(btn("+1") { s.good++; store.save(); updateOverview() })
        c2.addView(r1)
        ovScrap = tv("", 20f, bold = true)
        val r2 = hRow()
        r2.addW(ovScrap!!, 2f)
        r2.addW(btn("−1", outlined = true) { if (s.scrap > 0) s.scrap--; store.save(); updateOverview() })
        r2.addW(btn("+1") { s.scrap++; store.save(); updateOverview() })
        c2.addView(r2)

        // Stopp
        val c3 = card()
        ovStopBtn = btn("") { toggleManualStop() }
        c3.addView(ovStopBtn)
        ovSensor = tv("", 13f, color = grey)
        c3.addView(ovSensor)

        // Tider
        val c4 = card()
        c4.addView(tv("Tider", 14f, color = grey))
        ovTimes = tv("", 15f)
        c4.addView(ovTimes)
        c4.addView(btn("Idealcykeltid: ${num(s.idealCycleSec)} s – ändra", outlined = true) {
            askNumber("Idealcykeltid (sekunder per detalj)", num(s.idealCycleSec), true) {
                if (it > 0) {
                    s.idealCycleSec = it
                    store.lastIdealCycleSec = it
                    store.save()
                    render()
                }
            }
        })

        content.addView(btn("Avsluta skift", outlined = true) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Avsluta skiftet?")
                .setMessage("Pågående stopp avslutas och resultatet sparas.")
                .setPositiveButton("Avsluta") { _, _ ->
                    val now = System.currentTimeMillis()
                    s.openStop?.end = now
                    s.end = now
                    store.save()
                    render()
                }
                .setNegativeButton("Avbryt", null)
                .show()
        }, matchWrap().apply { topMargin = 8.dp() })
    }

    private fun updateOverview() {
        val s = store.shift ?: return
        if (s.end != null || ovOee == null) return
        val now = System.currentTimeMillis()
        val r = store.calc(s, now)

        val open = s.openStop
        if (open == null) {
            ovStatus?.text = "KÖR"
            ovStatus?.setBackgroundColor(green)
        } else {
            val what = if (open.reasonId == null) "Ej klassad" else store.reasonLabel(open.reasonId)
            ovStatus?.text = "STOPP ${dur(now - open.start)}\n$what"
            ovStatus?.setBackgroundColor(if (store.isPlanned(open)) amber else red)
        }
        ovOee?.text = pct(r.oee)
        ovOee?.setTextColor(oeeColor(r.oee))
        ovA?.text = "Tillgängl.\n${pct(r.availability)}"
        ovP?.text = "Prestanda\n${pct(r.performance)}"
        ovQ?.text = "Kvalitet\n${pct(r.quality)}"
        ovGood?.text = "Bra: ${s.good}"
        ovScrap?.text = "Kass: ${s.scrap}"
        ovTimes?.text = buildString {
            append("Skifttid: ${dur(r.totalMs)}\n")
            append("Planerade stopp: ${dur(r.plannedStopMs)}\n")
            append("Planerad produktionstid: ${dur(r.plannedProductionMs)}\n")
            append("Oplanerade stopp: ${dur(r.unplannedStopMs)}\n")
            append("Gångtid: ${dur(r.runMs)}")
        }
        ovStopBtn?.text = if (open == null) "Starta stopp manuellt" else "Avsluta stopp"
        val sensorName = when (store.sensorType) {
            "phone" -> "Telefonens sensor"
            "ble" -> store.bleName ?: "Bluetooth"
            else -> "Ingen"
        }
        ovSensor?.text = if (source != null) {
            val ago = (now - lastMotion) / 1000
            "Sensor: $sensorName · $sensorStatus\nSenaste rörelse för $ago s sedan · stopp efter ${store.thresholdSec} s"
        } else {
            "Sensor: $sensorName · $sensorStatus"
        }
    }

    private fun renderStops() {
        val s = store.shift
        if (s == null) {
            card().addView(tv("Inga stopp ännu. Starta ett skift på Översikt.", 16f))
            return
        }
        val now = System.currentTimeMillis()

        // Summering per kategori, längst först
        val perCat = linkedMapOf<String, Long>()
        for (st in s.stops) {
            val key = store.category(store.reason(st.reasonId)?.categoryId)?.name ?: "Ej klassad"
            perCat[key] = (perCat[key] ?: 0L) + ((st.end ?: now) - st.start)
        }
        val sum = card()
        sum.addView(tv("Stopptid per kategori", 14f, color = grey))
        if (perCat.isEmpty()) sum.addView(tv("Inga stopp registrerade", 15f))
        perCat.entries.sortedByDescending { it.value }.forEach {
            val row = hRow()
            row.addW(tv(it.key, 15f), 2f)
            row.addW(tv(dur(it.value), 15f, bold = true).apply { gravity = Gravity.END })
            sum.addView(row)
        }
        sum.addView(btn("Dela som CSV", outlined = true) { shareCsv(s) })

        content.addView(tv("Tryck på ett stopp för att välja orsak. Håll inne för att ta bort.", 13f, color = grey).apply {
            setPadding(4.dp(), 8.dp(), 4.dp(), 4.dp())
        })

        for (st in s.stops.reversed()) {
            val c = card()
            val endTxt = st.end?.let { hhmm.format(Date(it)) } ?: "pågår"
            val src = if (st.source == "sensor") "Sensor" else "Manuellt"
            c.addView(tv("${hhmm.format(Date(st.start))}–$endTxt · ${dur((st.end ?: now) - st.start)} · $src", 14f, color = grey))
            val label = store.reasonLabel(st.reasonId)
            c.addView(tv(label, 17f, bold = true, color = if (st.reasonId == null) red else null))
            val cardView = c.parent as View
            cardView.setOnClickListener { askReason(st) }
            cardView.setOnLongClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Ta bort stoppet?")
                    .setPositiveButton("Ta bort") { _, _ ->
                        s.stops.remove(st)
                        store.save()
                        render()
                    }
                    .setNegativeButton("Avbryt", null)
                    .show()
                true
            }
        }
    }

    private fun renderSettings() {
        // Sensor
        val c1 = card()
        c1.addView(tv("Rörelsesensor", 18f, bold = true))
        val rg = RadioGroup(this)
        val options = listOf("none" to "Ingen (bara manuella stopp)", "phone" to "Telefonens egen rörelsesensor", "ble" to "Bluetooth-sensor")
        options.forEachIndexed { i, (key, label) ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                isChecked = store.sensorType == key
                tag = key
            }
            rg.addView(rb)
        }
        rg.setOnCheckedChangeListener { group, checkedId ->
            val key = group.findViewById<RadioButton>(checkedId)?.tag as? String ?: return@setOnCheckedChangeListener
            store.sensorType = key
            startSource()
            if (key == "ble" && store.bleAddress == null) scanForDevices()
        }
        c1.addView(rg)
        c1.addView(tv("Status: $sensorStatus", 13f, color = grey))
        c1.addView(btn("Stopp efter ${store.thresholdSec} s utan rörelse – ändra", outlined = true) {
            askNumber("Sekunder utan rörelse innan stopp", store.thresholdSec.toString(), false) {
                if (it >= 1) {
                    store.thresholdSec = it.toInt()
                    render()
                }
            }
        })
        c1.addView(btn("Känslighet telefon: ${num(store.phoneSensitivity.toDouble())} m/s² – ändra", outlined = true) {
            askNumber("Hur kraftig rörelse räknas (m/s²). Lägre = känsligare.", num(store.phoneSensitivity.toDouble()), true) {
                if (it > 0) {
                    store.phoneSensitivity = it.toFloat()
                    render()
                }
            }
        })
        val bleTxt = store.bleAddress?.let { "${store.bleName ?: "Okänd"} ($it)" } ?: "ingen vald"
        c1.addView(tv("Bluetooth-enhet: $bleTxt", 14f))
        c1.addView(btn("Sök Bluetooth-enheter") { scanForDevices() })

        // Kategorier och orsaker
        val c2 = card()
        c2.addView(tv("Stoppkategorier och orsaker", 18f, bold = true))
        c2.addView(tv("Tryck för att ändra. Planerade kategorier räknas bort från planerad produktionstid.", 13f, color = grey))
        for (cat in store.categories) {
            val head = tv("${cat.name}${if (cat.planned) "  · planerat" else ""}", 16f, bold = true).apply {
                setPadding(0, 12.dp(), 0, 4.dp())
                setOnClickListener { editCategory(cat) }
            }
            c2.addView(head)
            for (r in store.reasons.filter { it.categoryId == cat.id }) {
                c2.addView(tv("   • ${r.name}", 15f).apply {
                    setPadding(0, 6.dp(), 0, 6.dp())
                    setOnClickListener { editReason(r) }
                })
            }
        }
        val row = hRow()
        row.addW(btn("Ny kategori", outlined = true) { editCategory(null) })
        row.addW(btn("Ny orsak") { editReason(null) })
        c2.addView(row)
    }

    // ---------------------------------------------------------------- åtgärder

    private fun startShift() {
        askNumber("Idealcykeltid (sekunder per detalj)", num(store.lastIdealCycleSec), true) {
            if (it <= 0) return@askNumber
            store.lastIdealCycleSec = it
            val now = System.currentTimeMillis()
            store.shift = Shift(now, null, it, 0, 0, mutableListOf())
            lastMotion = now
            store.save()
            render()
        }
    }

    private fun toggleManualStop() {
        val s = store.shift ?: return
        val now = System.currentTimeMillis()
        val open = s.openStop
        if (open == null) {
            val st = Stop(newId(), now, null, null, "manuell")
            s.stops.add(st)
            store.save()
            updateOverview()
            askReason(st)
        } else {
            open.end = now
            lastMotion = now
            store.save()
            updateOverview()
            if (open.reasonId == null) askReason(open)
        }
    }

    private fun askReason(stop: Stop) {
        val ordered = store.categories.flatMap { c -> store.reasons.filter { it.categoryId == c.id } }
        if (ordered.isEmpty()) return
        val labels = ordered.map { store.reasonLabel(it.id) }.toTypedArray()
        val len = dur((stop.end ?: System.currentTimeMillis()) - stop.start)
        MaterialAlertDialogBuilder(this)
            .setTitle("Stopp $len – välj orsak")
            .setItems(labels) { _, which ->
                stop.reasonId = ordered[which].id
                store.save()
                render()
            }
            .setNegativeButton("Senare", null)
            .show()
    }

    private fun editCategory(cat: Category?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 8.dp(), 20.dp(), 0)
        }
        val name = EditText(this).apply {
            hint = "Namn"
            setText(cat?.name ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val planned = CheckBox(this).apply {
            text = "Planerat stopp (t.ex. rast)"
            isChecked = cat?.planned == true
        }
        box.addView(name)
        box.addView(planned)
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(if (cat == null) "Ny kategori" else "Ändra kategori")
            .setView(box)
            .setPositiveButton("Spara") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                if (cat == null) store.categories.add(Category(newId(), n, planned.isChecked))
                else {
                    cat.name = n
                    cat.planned = planned.isChecked
                }
                store.save()
                render()
            }
            .setNegativeButton("Avbryt", null)
        if (cat != null) b.setNeutralButton("Ta bort") { _, _ ->
            store.reasons.removeAll { it.categoryId == cat.id }
            store.categories.remove(cat)
            store.save()
            render()
        }
        b.show()
    }

    private fun editReason(reason: Reason?) {
        if (store.categories.isEmpty()) {
            toast("Skapa en kategori först")
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 8.dp(), 20.dp(), 0)
        }
        val name = EditText(this).apply {
            hint = "Orsak"
            setText(reason?.name ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        box.addView(name)
        box.addView(tv("Kategori", 13f, color = grey).apply { setPadding(0, 8.dp(), 0, 0) })
        val rg = RadioGroup(this)
        store.categories.forEach { c ->
            rg.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = c.name
                tag = c.id
                isChecked = (reason?.categoryId ?: store.categories.first().id) == c.id
            })
        }
        box.addView(rg)
        val scroll = ScrollView(this).apply { addView(box) }
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(if (reason == null) "Ny orsak" else "Ändra orsak")
            .setView(scroll)
            .setPositiveButton("Spara") { _, _ ->
                val n = name.text.toString().trim()
                val catId = rg.findViewById<RadioButton>(rg.checkedRadioButtonId)?.tag as? String
                if (n.isEmpty() || catId == null) return@setPositiveButton
                if (reason == null) store.reasons.add(Reason(newId(), n, catId))
                else {
                    reason.name = n
                    reason.categoryId = catId
                }
                store.save()
                render()
            }
            .setNegativeButton("Avbryt", null)
        if (reason != null) b.setNeutralButton("Ta bort") { _, _ ->
            store.reasons.remove(reason)
            store.save()
            render()
        }
        b.show()
    }

    private fun shareCsv(s: Shift) {
        val r = store.calc(s, System.currentTimeMillis())
        val full = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", sv)
        val text = buildString {
            append("OEE;${pct(r.oee)}\n")
            append("Tillgänglighet;${pct(r.availability)}\n")
            append("Prestanda;${pct(r.performance)}\n")
            append("Kvalitet;${pct(r.quality)}\n")
            append("Bra;${s.good}\nKass;${s.scrap}\n")
            append("Idealcykeltid s;${num(s.idealCycleSec)}\n\n")
            append("Start;Slut;Minuter;Källa;Kategori;Orsak\n")
            for (st in s.stops) {
                val end = st.end ?: System.currentTimeMillis()
                val reason = store.reason(st.reasonId)
                val cat = store.category(reason?.categoryId)
                val min = String.format(sv, "%.1f", (end - st.start) / 60000.0)
                append("${full.format(Date(st.start))};${st.end?.let { full.format(Date(it)) } ?: "pågår"};$min;${st.source};${cat?.name ?: ""};${reason?.name ?: "Ej klassad"}\n")
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "OEE-skift ${full.format(Date(s.start))}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Dela skiftet"))
    }

    // ---------------------------------------------------------------- Bluetooth-sökning

    private fun blePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasBlePermissions(): Boolean = blePermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun scanForDevices() {
        if (!hasBlePermissions()) {
            requestPermissions(blePermissions(), 42)
            return
        }
        showScanDialog()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42) {
            if (hasBlePermissions()) showScanDialog() else toast("Bluetooth-behörighet behövs för att hitta sensorn")
        }
    }

    @SuppressLint("MissingPermission")
    private fun showScanDialog() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            toast("Telefonen saknar Bluetooth")
            return
        }
        if (!adapter.isEnabled) {
            toast("Slå på Bluetooth först")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            toast("Kunde inte starta sökningen")
            return
        }
        val labels = ArrayList<String>()
        val addresses = ArrayList<String>()
        val names = ArrayList<String>()
        val list = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val a = result.device.address
                if (a in addresses) return
                val n = result.device.name ?: result.scanRecord?.deviceName ?: "Okänd enhet"
                addresses.add(a)
                names.add(n)
                labels.add("$n\n$a · ${result.rssi} dBm")
                list.notifyDataSetChanged()
            }
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Söker… välj din sensor")
            .setAdapter(list) { _, which ->
                store.bleAddress = addresses[which]
                store.bleName = names[which]
                store.sensorType = "ble"
                startSource()
                render()
            }
            .setNegativeButton("Avbryt", null)
            .create()
        dialog.setOnDismissListener {
            try {
                scanner.stopScan(cb)
            } catch (_: Exception) {
            }
        }
        dialog.show()
        try {
            scanner.startScan(cb)
        } catch (e: SecurityException) {
            toast("Bluetooth-behörighet saknas")
        }
    }

    // ---------------------------------------------------------------- hjälpfunktioner

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).roundToInt()

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun tv(text: String, size: Float = 16f, bold: Boolean = false, color: Int? = null) = TextView(this).apply {
        this.text = text
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (color != null) setTextColor(color)
    }

    private fun btn(label: String, outlined: Boolean = false, onClick: () -> Unit): MaterialButton {
        val b = if (outlined) MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        else MaterialButton(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun spacer() = View(this).apply { minimumHeight = 8.dp() }

    private fun hRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun LinearLayout.addW(v: View, weight: Float = 1f) {
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        lp.marginEnd = 4.dp()
        addView(v, lp)
    }

    /** Lägger till ett kort i innehållet och returnerar dess inre layout. */
    private fun card(): LinearLayout {
        val cv = MaterialCardView(this).apply {
            radius = 12.dp().toFloat()
            isClickable = true
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
        }
        cv.addView(inner)
        content.addView(cv, matchWrap().apply { bottomMargin = 8.dp() })
        return inner
    }

    private fun askNumber(title: String, current: String, decimal: Boolean, onOk: (Double) -> Unit) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            setText(current)
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply {
            setPadding(20.dp(), 8.dp(), 20.dp(), 0)
            addView(et, matchWrap())
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                et.text.toString().replace(',', '.').toDoubleOrNull()?.let(onOk)
            }
            .setNegativeButton("Avbryt", null)
            .show()
    }

    private fun pct(d: Double) = String.format(sv, "%.1f %%", d * 100)

    private fun num(d: Double) =
        if (d == Math.floor(d)) d.toLong().toString() else String.format(sv, "%.2f", d).trimEnd('0').trimEnd(',')

    private fun dur(ms: Long): String {
        val t = maxOf(0L, ms) / 1000
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) String.format(sv, "%d:%02d:%02d", h, m, s) else String.format(sv, "%02d:%02d", m, s)
    }

    private fun oeeColor(d: Double) = when {
        d >= 0.85 -> green
        d >= 0.60 -> amber
        else -> red
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun vibrate() {
        try {
            @Suppress("DEPRECATION")
            val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }
}

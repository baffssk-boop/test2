package se.oeeapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import kotlin.math.sqrt

/** Något som kan säga "nu rörde sig maskinen". */
interface MotionSource {
    fun start()
    fun stop()
}

/**
 * Telefonens egen accelerometer. Gravitationen filtreras bort och om den
 * kvarvarande accelerationen överstiger känsligheten räknas det som rörelse.
 */
class PhoneMotionSource(
    context: Context,
    private val sensitivity: () -> Float,
    private val onMotion: () -> Unit,
    private val onStatus: (String) -> Unit,
) : MotionSource, SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravity = FloatArray(3)
    private var primed = 0

    override fun start() {
        val s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (s == null) {
            onStatus("Telefonen saknar rörelsesensor")
            return
        }
        primed = 0
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        onStatus("Telefonens sensor aktiv")
    }

    override fun stop() {
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val alpha = 0.9f
        for (i in 0..2) gravity[i] = alpha * gravity[i] + (1 - alpha) * e.values[i]
        if (primed < 30) {
            // Låt filtret svänga in innan vi börjar bedöma rörelse
            primed++
            return
        }
        val x = e.values[0] - gravity[0]
        val y = e.values[1] - gravity[1]
        val z = e.values[2] - gravity[2]
        if (sqrt(x * x + y * y + z * z) > sensitivity()) onMotion()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/**
 * Generisk Bluetooth LE-sensor. Ansluter till vald enhet, slår på aviseringar
 * för alla egenskaper som stöder det, och tolkar varje avisering som rörelse.
 */
@SuppressLint("MissingPermission")
class BleMotionSource(
    private val context: Context,
    private val address: String,
    private val onMotion: () -> Unit,
    private val onStatus: (String) -> Unit,
) : MotionSource {

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var running = false
    private var notifications = 0
    private val pending = ArrayDeque<BluetoothGattCharacteristic>()

    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    override fun start() {
        running = true
        connect()
    }

    override fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    private fun report(s: String) = main.post { onStatus(s) }

    private fun connect() {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bm.adapter
        if (adapter == null || !adapter.isEnabled) {
            report("Bluetooth är avstängt")
            retryLater()
            return
        }
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: Exception) {
            report("Ogiltig enhet")
            return
        }
        report("Ansluter…")
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            report("Saknar Bluetooth-behörighet")
        }
    }

    private fun retryLater() {
        if (!running) return
        main.postDelayed({ if (running) connect() }, 5000)
    }

    private fun enableNext(g: BluetoothGatt) {
        val ch = pending.removeFirstOrNull()
        if (ch == null) {
            report("Ansluten · väntar på rörelse")
            return
        }
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(cccd)
        if (d == null) {
            enableNext(g)
            return
        }
        val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value)
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private fun gotNotification() {
        notifications++
        val n = notifications
        main.post {
            onMotion()
            onStatus("Ansluten · $n signaler")
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, gattStatus: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                report("Ansluten · läser tjänster…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                report("Frånkopplad · försöker igen")
                try {
                    g.close()
                } catch (_: Exception) {
                }
                if (gatt == g) gatt = null
                retryLater()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, gattStatus: Int) {
            pending.clear()
            for (s in g.services) {
                for (c in s.characteristics) {
                    val p = c.properties
                    if (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        pending.addLast(c)
                    }
                }
            }
            if (pending.isEmpty()) {
                report("Enheten skickar inga aviseringar")
                return
            }
            enableNext(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, gattStatus: Int) {
            enableNext(g)
        }

        // Android 12 och äldre
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            gotNotification()
        }

        // Android 13 och nyare
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            gotNotification()
        }
    }
}

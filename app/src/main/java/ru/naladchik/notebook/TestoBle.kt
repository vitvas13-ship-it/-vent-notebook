package ru.naladchik.notebook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Bluetooth-рукоятки Testo (0554 1111, имя «UpHandle …») с зондами: крыльчатка, струна, влажность.
 * Можно несколько рукояток сразу — у каждой своё подключение; события несут поле "h" (имя рукоятки).
 *
 * Только чтение замеров. При подключении посылаются ровно те кадры, что посылает
 * приложение Testo (сняты из журнала Bluetooth): начало сессии, чтение свойств,
 * подписка на замеры рукоятки и зонда. Команд настройки/калибровки/прошивки нет.
 *
 * Кадр: cmd(1) seq(1) длина(u32 LE) CRC16/MODBUS заголовка, затем данные + их CRC16.
 * Замер зонда приходит кадром cmd 0x22: плата(1) 0x10 имя(строка) float32 статус(u16).
 */
@SuppressLint("MissingPermission")
class TestoBle(private val ctx: Context, private val emit: (JSONObject) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var scanning = false
    private val found = LinkedHashMap<String, BluetoothDevice>()
    private val conns = LinkedHashMap<String, Conn>()
    /** Общее состояние: off · scan · connecting · on · err */
    var state = "off"; private set

    private fun send(o: JSONObject) = main.post { emit(o) }
    private fun setState(s: String, msg: String = "") {
        state = s
        send(JSONObject().put("e", "state").put("s", s).put("msg", msg))
    }
    /** Общее состояние по всем рукояткам. */
    private fun sumState(msg: String = "") {
        val s = when {
            scanning -> "scan"
            conns.values.any { it.st == "on" } -> "on"
            conns.values.any { it.st == "connecting" } -> "connecting"
            else -> "off"
        }
        if (s != state || msg.isNotEmpty()) setState(s, msg)
    }

    fun start() {
        val a = adapter
        if (a == null) { setState("err", "На телефоне нет Bluetooth."); return }
        if (!a.isEnabled) { setState("err", "Включите Bluetooth на телефоне."); return }
        if (scanning) { setState(state); return }
        val sc = a.bluetoothLeScanner
        if (sc == null) { setState("err", "Bluetooth не готов, попробуйте ещё раз."); return }
        found.clear()
        scanning = true
        sumState()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try { sc.startScan(null, settings, scanCb) } catch (e: Exception) { scanning = false; setState("err", "Не удалось начать поиск: ${e.message}"); return }
        main.postDelayed({ if (scanning) finishScan() }, 12000)
    }

    /** Поиск закончен: подключаем все найденные рукоятки по очереди. */
    private fun finishScan() {
        stopScan()
        val list = found.values.toList(); found.clear()
        if (list.isEmpty()) {
            if (conns.isEmpty()) setState("err", "Рукоятка не найдена. Включите её и закройте приложение Testo на телефоне (и отключите рукоятку на testo 400).")
            else sumState("Других рукояток рядом не найдено.")
            return
        }
        list.forEachIndexed { i, d -> main.postDelayed({ connect(d) }, i * 700L) }
        sumState()
    }

    fun stop() {
        stopScan(); found.clear()
        for (c in conns.values.toList()) c.close()
        conns.clear()
        if (state != "off") setState("off")
    }

    /** Отключить одну рукоятку по имени («UpHandle …»). */
    fun stopOne(name: String) {
        val c = conns.values.firstOrNull { it.name == name } ?: return
        conns.remove(c.dev.address)
        c.close()
        sumState()
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val name = result.scanRecord?.deviceName ?: try { result.device.name } catch (_: Exception) { null } ?: return
            if (!name.startsWith("UpHandle", ignoreCase = true)) return
            val addr = result.device.address
            if (conns.containsKey(addr) || found.containsKey(addr)) return
            found[addr] = result.device
            // первая найденная — ждём ещё 3 с: вдруг рядом вторая рукоятка
            if (found.size == 1) main.postDelayed({ if (scanning) finishScan() }, 3000)
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            setState("err", "Поиск Bluetooth не удался (код $errorCode).")
        }
    }

    private fun connect(d: BluetoothDevice) {
        if (conns.containsKey(d.address)) return
        val c = Conn(d); conns[d.address] = c
        c.hstate("connecting")
        sumState()
        c.gatt = d.connectGatt(ctx, false, c.cb, BluetoothDevice.TRANSPORT_LE)
    }

    /** Одна рукоятка: своё GATT-подключение, очередь записи и сборка кадров. */
    private inner class Conn(val dev: BluetoothDevice) {
        val name: String = try { dev.name ?: dev.address } catch (_: Exception) { dev.address }
        var gatt: BluetoothGatt? = null
        var wr: BluetoothGattCharacteristic? = null
        val queue = ArrayDeque<ByteArray>()
        val buf = java.io.ByteArrayOutputStream()
        var st = "connecting"
        var probeId = ""

        fun out(o: JSONObject) = send(o.put("h", name))
        fun hstate(s: String, msg: String = "") { st = s; out(JSONObject().put("e", "hstate").put("s", s).put("msg", msg)) }

        fun close() {
            queue.clear()
            try { gatt?.disconnect() } catch (_: Exception) {}
            try { gatt?.close() } catch (_: Exception) {}
            gatt = null; wr = null
            if (st != "off") hstate("off")
        }

        fun lost(msg: String) {
            main.post {
                try { gatt?.close() } catch (_: Exception) {}
                gatt = null; wr = null; queue.clear()
                if (conns[dev.address] === this) conns.remove(dev.address)
                hstate("off", msg)
                sumState(msg)
            }
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    main.post { g.discoverServices() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val hn = name.replace("UpHandle", "Рукоятка").trim()
                    lost(if (st == "connecting") "$hn: не удалось подключиться." else "$hn отключилась.")
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val s = g.getService(SVC)
                val w = s?.getCharacteristic(CH_WR)
                val n = s?.getCharacteristic(CH_NT)
                if (w == null || n == null) { main.post { close(); conns.remove(dev.address); sumState("Это не рукоятка Testo (нет нужного сервиса).") }; return }
                wr = w
                g.setCharacteristicNotification(n, true)
                val d = n.getDescriptor(CCCD)
                if (d == null) { main.post { close(); conns.remove(dev.address); sumState("Рукоятка не даёт подписку на замеры.") }; return }
                val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v)
                else { @Suppress("DEPRECATION") run { d.value = v; g.writeDescriptor(d) } }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                main.post {
                    queue.clear()
                    for (hx in STARTUP) enqueue(hx)
                    main.postDelayed({ writeNext() }, 300)
                }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                main.post { writeNext() }
            }

            @Deprecated("до Android 13")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION") c.value?.let { feed(it) }
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                feed(value)
            }
        }

        /** Кадр порциями по 20 байт (MTU рукоятки 23), пустая порция — пауза между кадрами. */
        fun enqueue(hx: String) {
            val fr = hex(hx); var i = 0
            while (i < fr.size) { queue.add(fr.copyOfRange(i, minOf(i + 20, fr.size))); i += 20 }
            queue.add(ByteArray(0))
        }

        fun writeNext() {
            val g = gatt ?: return
            val w = wr ?: return
            val chunk = queue.removeFirstOrNull()
            if (chunk == null) { if (st != "on") { hstate("on"); sumState() }; return }
            if (chunk.isEmpty()) { main.postDelayed({ writeNext() }, 250); return }
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(w, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
            } else {
                @Suppress("DEPRECATION") run {
                    w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    w.value = chunk; g.writeCharacteristic(w)
                }
            }
            if (!ok) { queue.addFirst(chunk); main.postDelayed({ writeNext() }, 200) }
        }

        /** Сборка кадров из уведомлений (кадр может прийти кусками). */
        @Synchronized
        fun feed(data: ByteArray) {
            buf.write(data)
            var b = buf.toByteArray()
            while (b.size >= 8) {
                val ln = ByteBuffer.wrap(b, 2, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (ln < 0 || ln > 100000) { b = ByteArray(0); break }
                if (b.size < 8 + ln) break
                val fr = b.copyOfRange(0, 8 + ln)
                b = b.copyOfRange(8 + ln, b.size)
                try { frame(fr) } catch (_: Exception) {}
            }
            buf.reset(); buf.write(b)
        }

        fun frame(t: ByteArray) {
            val cmd = t[0].toInt() and 0xFF
            val ln = ByteBuffer.wrap(t, 2, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (ln < 2) return
            val body = t.copyOfRange(8, t.size - 2)
            val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            if (cmd == 0x22 && body.size >= 6) {
                // плата, 0x10, имя, float32, статус
                val n = bb.getInt(2)
                if (n <= 0 || 6 + n + 6 > body.size) return
                val nm = String(body, 6, n, Charsets.US_ASCII)
                val v = bb.getFloat(6 + n)
                val stt = bb.getShort(6 + n + 4).toInt() and 0xFFFF
                out(JSONObject().put("e", "val").put("n", nm).put("v", v.toDouble()).put("st", stt))
            } else if (cmd == 0x10 && body.size >= 5) {
                val n = bb.getInt(0)
                if (n <= 0 || 4 + n > body.size) return
                when (String(body, 4, n, Charsets.US_ASCII)) {
                    // оранжевая кнопка на рукоятке
                    "ButtonClick" -> out(JSONObject().put("e", "btn"))
                    "BatteryLevel" -> if (4 + n + 4 <= body.size) out(JSONObject().put("e", "bat").put("v", bb.getFloat(4 + n).toDouble()))
                    // тип зонда строкой, приходит и при смене зонда
                    "SensorType" -> {
                        val id = str(body, 4 + n) ?: return
                        val was = probeId; probeId = id
                        out(JSONObject().put("e", "probe").put("id", id))
                        if (was.isNotEmpty() && was != id) main.post { enqueue(STARTUP[4]); enqueue(STARTUP[5]); writeNext() }
                    }
                }
            } else if (cmd == 0x07 && (t[1].toInt() and 0xFF) == 0x04 && body.size >= 12) {
                // ответ на «Measboards.Serials»: число зондов (u32), заводской номер (u64)
                val sn = bb.getLong(4)
                if (sn > 0) out(JSONObject().put("e", "dev").put("sn", sn.toString()).put("handle", name))
            } else if (cmd == 0x07 && (t[1].toInt() and 0xFF) == 0x05 && body.size >= 9) {
                // ответ на «Measboards.DeviceIds»: число зондов (u32), тип зонда строкой
                val id = str(body, 4) ?: return
                probeId = id
                out(JSONObject().put("e", "probe").put("id", id))
            }
        }
    }

    /** Строка в кадре: u32 длина + ASCII. */
    private fun str(b: ByteArray, at: Int): String? {
        if (at + 4 > b.size) return null
        val n = ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (n <= 0 || n > 64 || at + 4 + n > b.size) return null
        return String(b, at + 4, n, Charsets.US_ASCII)
    }

    companion object {
        private val SVC: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        private val CH_WR: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val CH_NT: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Кадры, которые приложение Testo посылает рукоятке при подключении (из журнала Bluetooth). */
        private val STARTUP = listOf(
            "5600030000000c69023e81",
            "2001000000003abb",
            "0402150000007c530f0000004669726d7761726556657273696f6e304f",
            "04031600000041d7100000004d656173626f617264732e436f756e74d30b",
            "040418000000f6ff120000004d656173626f617264732e53657269616c7367d0",
            "04051a000000ca87140000004d656173626f617264732e4465766963654964737f57",
            "2306030000008f0c00bf40",
            "210705000000b3a60020032801",
            "210818000000e1cb0004030f0000004669726d7761726556657273696f6e4ed7",
            "21091d000000dcc70004031400000041637469766174655365727669636541646170747dab",
            "210a1900000099f7000403100000004d6561737572656d656e744379636c657941",
            "210b13000000a7ef0004030a000000446576696365496e666fc875",
            "210c14000000135b0004030b00000043616c44617461557365724434",
            "210d1d0000002d070004031400000041637469766174655365727669636541646170747dab",
            "210e1a0000006873000403110000004163746976617465557365724164617074a7d8",
            "210f1d00000054c70004031400000041637469766174655365727669636541646170747dab",
            "21101a000000c071000403110000004163746976617465557365724164617074a7d8",
            "111100000000ff59",
            "211205000000be650011033d91",
        )

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

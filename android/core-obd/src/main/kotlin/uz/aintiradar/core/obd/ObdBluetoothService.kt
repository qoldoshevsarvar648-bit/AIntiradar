package uz.aintiradar.core.obd

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Avtomobil ECU (OBD-II) telemetriyasi
 */
data class VehicleTelemetry(
    val rpm: Int = 0,
    val speedKmH: Int = 0,
    val throttlePercent: Double = 0.0,
    val coolantTempCelsius: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

sealed interface ObdConnectionState {
    object Disconnected : ObdConnectionState
    object Connecting : ObdConnectionState
    object Initializing : ObdConnectionState
    data class Connected(val deviceName: String) : ObdConnectionState
    data class Error(val message: String) : ObdConnectionState
}

/**
 * ELM327 Bluetooth Classic & BLE uchun Foreground Service.
 * Avtomobil CAN-Bus/ECU blokidan RPM, tezlik, gaz pedali bosimi va dvigatel haroratini
 * real vaqtda yuqori chastotada (10-20 Hz) o'qiydi.
 */
class ObdBluetoothService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "ainti_radar_obd_channel"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        const val ACTION_CONNECT = "uz.aintiradar.core.obd.CONNECT"
        const val ACTION_DISCONNECT = "uz.aintiradar.core.obd.DISCONNECT"
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"

        // Global oqimlar ilovaning barcha qismlari uchun
        private val _telemetryFlow = MutableStateFlow(VehicleTelemetry())
        val telemetryFlow: StateFlow<VehicleTelemetry> = _telemetryFlow.asStateFlow()

        private val _connectionState = MutableStateFlow<ObdConnectionState>(ObdConnectionState.Disconnected)
        val connectionState: StateFlow<ObdConnectionState> = _connectionState.asStateFlow()
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var targetDeviceAddress: String? = null
    private var isStreaming = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("OBD-II tayyorlanmoqda..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                targetDeviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                startConnectionLoop()
            }
            ACTION_DISCONNECT -> {
                stopConnection()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startConnectionLoop() {
        val address = targetDeviceAddress ?: return
        serviceScope.launch {
            var attempt = 1
            while (isActive && !isStreaming) {
                try {
                    _connectionState.value = ObdConnectionState.Connecting
                    connectToElm327(address)
                    break
                } catch (e: Exception) {
                    _connectionState.value = ObdConnectionState.Error("Ulanish xatosi (urinish $attempt): ${e.localizedMessage}")
                    val backoffMs = (attempt * 2000L).coerceAtMost(10000L)
                    delay(backoffMs)
                    attempt++
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectToElm327(deviceAddress: String) = withContext(Dispatchers.IO) {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IllegalStateException("Qurilmada Bluetooth mavjud emas")

        val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(deviceAddress)
        bluetoothAdapter.cancelDiscovery()

        bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID).apply {
            connect()
        }

        inputStream = bluetoothSocket?.inputStream
        outputStream = bluetoothSocket?.outputStream

        _connectionState.value = ObdConnectionState.Initializing
        initializeElm327Protocol()

        _connectionState.value = ObdConnectionState.Connected(device.name ?: "ELM327 OBD")
        isStreaming = true

        // Telemetriyani doimiy o'qish sikli
        pollTelemetryLoop()
    }

    /**
     * ELM327 protokoli uchun standart AT initsializatsiya ketma-ketligi
     */
    private suspend fun initializeElm327Protocol() {
        val initCommands = listOf(
            "ATZ",      // Reset
            "ATE0",     // Echo Off (ortiqcha baytlarni o'chirish)
            "ATL0",     // Linefeeds Off
            "ATS0",     // Bo'shliqlarni (Spaces) o'chirish
            "ATH0",     // Sarlavhalarni (Headers) o'chirish
            "ATAT1",    // Adaptive Timing Auto 1
            "ATSP0"     // Protocol Auto-detect (ISO 15765-4 CAN va boshqalar)
        )

        for (cmd in initCommands) {
            sendCommand(cmd)
            delay(150) // Har bir AT buyrug'i uchun kontroller javob berishi kutiladi
        }
    }

    /**
     * PID so'rovlarini uzluksiz o'qish (RPM, Speed, Throttle, Coolant)
     */
    private suspend fun pollTelemetryLoop() = withContext(Dispatchers.IO) {
        var currentTelemetry = VehicleTelemetry()

        while (isActive && isStreaming) {
            try {
                // 1. PID 010C - Engine RPM (2 bayt: ((A*256)+B)/4)
                val rpmResponse = sendCommand("010C")
                val rpm = parseRpm(rpmResponse)

                // 2. PID 010D - Vehicle Speed (1 bayt: A km/h)
                val speedResponse = sendCommand("010D")
                val speed = parseSpeed(speedResponse)

                // 3. PID 0111 - Throttle Position (1 bayt: (A*100)/255 %)
                val throttleResponse = sendCommand("0111")
                val throttle = parseThrottle(throttleResponse)

                // 4. PID 0105 - Engine Coolant Temperature (1 bayt: A - 40 °C)
                val coolantResponse = sendCommand("0105")
                val coolant = parseCoolant(coolantResponse)

                currentTelemetry = VehicleTelemetry(
                    rpm = rpm ?: currentTelemetry.rpm,
                    speedKmH = speed ?: currentTelemetry.speedKmH,
                    throttlePercent = throttle ?: currentTelemetry.throttlePercent,
                    coolantTempCelsius = coolant ?: currentTelemetry.coolantTempCelsius,
                    timestamp = System.currentTimeMillis()
                )

                _telemetryFlow.value = currentTelemetry
                delay(60) // ~16Hz yangilanish sikli (avtomobil CAN-shinasini to'ldirib qo'ymaslik uchun)

            } catch (e: Exception) {
                if (isActive) {
                    _connectionState.value = ObdConnectionState.Error("O'qish uzildi: ${e.message}")
                    isStreaming = false
                    startConnectionLoop()
                }
                break
            }
        }
    }

    private fun sendCommand(command: String): String {
        val out = outputStream ?: throw IllegalStateException("Chiqish oqimi mavjud emas")
        val input = inputStream ?: throw IllegalStateException("Kirish oqimi mavjud emas")

        out.write((command + "\r").toByteArray(Charsets.US_ASCII))
        out.flush()

        val sb = StringBuilder()
        var b: Int
        while (true) {
            b = input.read()
            if (b == -1) break
            val c = b.toChar()
            if (c == '>') break // ELM327 buyruq yakunini '>' belgisi bilan bildiradi
            sb.append(c)
        }
        return sb.toString().trim().replace(" ", "").replace("\r", "").replace("\n", "")
    }

    // --- Parserlar ---

    private fun parseRpm(raw: String): Int? {
        val clean = cleanResponse(raw, "410C") ?: return null
        if (clean.length < 4) return null
        return try {
            val a = clean.substring(0, 2).toInt(16)
            val b = clean.substring(2, 4).toInt(16)
            ((a * 256) + b) / 4
        } catch (_: Exception) { null }
    }

    private fun parseSpeed(raw: String): Int? {
        val clean = cleanResponse(raw, "410D") ?: return null
        if (clean.length < 2) return null
        return try {
            clean.substring(0, 2).toInt(16)
        } catch (_: Exception) { null }
    }

    private fun parseThrottle(raw: String): Double? {
        val clean = cleanResponse(raw, "4111") ?: return null
        if (clean.length < 2) return null
        return try {
            val a = clean.substring(0, 2).toInt(16)
            (a * 100.0) / 255.0
        } catch (_: Exception) { null }
    }

    private fun parseCoolant(raw: String): Int? {
        val clean = cleanResponse(raw, "4105") ?: return null
        if (clean.length < 2) return null
        return try {
            val a = clean.substring(0, 2).toInt(16)
            a - 40
        } catch (_: Exception) { null }
    }

    private fun cleanResponse(raw: String, prefix: String): String? {
        val idx = raw.indexOf(prefix)
        return if (idx != -1) raw.substring(idx + prefix.length) else null
    }

    private fun stopConnection() {
        isStreaming = false
        try {
            inputStream?.close()
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (_: Exception) {}
        _connectionState.value = ObdConnectionState.Disconnected
    }

    override fun onDestroy() {
        stopConnection()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OBD-II CAN-Bus Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Avtomobil telemetriyasini fonda kuzatish"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AIntiRADAR - ECU Telemetriya")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }
}

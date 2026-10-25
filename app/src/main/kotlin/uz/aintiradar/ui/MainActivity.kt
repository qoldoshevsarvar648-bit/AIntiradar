package uz.aintiradar.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (!locationGranted) {
            Toast.makeText(this, "Radarlarni aniqlash uchun geolokatsiya zarur!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()

        setContent {
            // Demo simulyatsiya ko'rsatkichlari (Real avtomobilsiz ham to'liq sinovdan o'tkazish uchun)
            var currentSpeed by remember { mutableIntStateOf(65) }
            var speedLimit by remember { mutableIntStateOf(70) }
            var obdRpm by remember { mutableIntStateOf(2100) }
            var distanceToRadar by remember { mutableStateOf<Int?>(450) }
            var aiMessage by remember {
                mutableStateOf("Hammasi joyida. Oldinda 450 metrda AutoCon kamerasi, ruxsat etilgan tezlik 70 km/soat.")
            }

            // Tezlik va radarga yaqinlashish simulyatsiyasi
            LaunchedEffect(Unit) {
                while (true) {
                    delay(1200)
                    if (distanceToRadar != null && distanceToRadar!! > 50) {
                        distanceToRadar = distanceToRadar!! - 25
                        if (distanceToRadar!! < 200 && currentSpeed > speedLimit + 5) {
                            aiMessage = "Diqqat! Tezlikni zudlik bilan 70 ga tushiring!"
                        }
                    } else {
                        distanceToRadar = 600
                        aiMessage = "Radar zonasidan muvaffaqiyatli o'tildi. Xavfsiz safar tilayman!"
                    }
                }
            }

            DashboardScreen(
                currentSpeedKmH = currentSpeed,
                speedLimitKmH = speedLimit,
                obdRpm = obdRpm,
                coolantTemp = 89,
                isObdConnected = true,
                activeThreatDistanceM = distanceToRadar,
                activeThreatName = "AutoCon - Toshkent Kichik Halqa Yo'li",
                aiVoiceMessage = aiMessage,
                onReportHazardClick = { hazardType ->
                    Toast.makeText(this, "Xavf saqlandi ($hazardType). Boshqa haydovchilarga uzatildi!", Toast.LENGTH_SHORT).show()
                },
                onVoiceCoPilotClick = {
                    Toast.makeText(this, "Co-Pilot: 'Sizni eshityapman, qayerga boramiz?'", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

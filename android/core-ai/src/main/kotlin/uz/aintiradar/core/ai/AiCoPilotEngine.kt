package uz.aintiradar.core.ai

import uz.aintiradar.core.geometry.RadarPoint
import uz.aintiradar.core.geometry.RadarThreatAssessment
import uz.aintiradar.core.geometry.ThreatLevel
import uz.aintiradar.core.obd.VehicleTelemetry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Tashqi ob-havo holati
 */
enum class WeatherCondition {
    CLEAR,
    RAIN,
    SNOW_OR_ICE,
    DENSE_FOG
}

/**
 * Kun qismi
 */
enum class DayTimeSlot {
    MORNING,
    AFTERNOON,
    EVENING,
    NIGHT
}

/**
 * Co-Pilot qaror natijasi (Ovozli va vizual signallar)
 */
data class CoPilotDecision(
    val priority: Priority,
    val spokenMessageUz: String,
    val recommendedSpeedKmH: Int,
    val isEmergencyBrakeRequired: Boolean,
    val visualWarningColor: WarningColor,
    val playAlertSound: Boolean
) {
    enum class Priority { HIGH, MEDIUM, LOW, ADVISORY }
    enum class WarningColor { GREEN, YELLOW, RED, FLASHING_RED }
}

/**
 * AI Co-Pilot Integratsiyalashgan Qarorlar Dvigateli
 *
 * 2 Qatlamli arxitektura:
 * 1. Hard Real-Time Arbiter (< 5ms): Foydalanuvchi xavfsizligi kechikmasligi uchun
 *    deterministik tezkor xavf qoidalari.
 * 2. LLM Proactive Co-Pilot: Haydovchi charchog'i, ob-havo va yo'l holatiga qarab
 *    o'zbek tilida kontekstual aqlli tavsiyalar beruvchi AI agenti.
 */
class AiCoPilotEngine {

    companion object {
        const val SYSTEM_PROMPT_UZBEK = """
Siz "AIntiRADAR" ilovasining shaxsiy avtomobil AI Co-Pilot maslahatchisisiz.
Vazifangiz: Haydovchini O'zbekiston yo'llarida xavfsiz, tejamkor va jarimalarsiz harakatlanishiga ko'maklashish.

QOIDALAR:
1. Haydovchi rulda ekanligini unutmang! Javoblar juda qisqa, aniq, lo'nda va tabiiy o'zbek tilida bo'lishi shart (maksimum 1-2 jumla).
2. Xavf darajasiga qarab ohangni o'zgartiring:
   - Kritik vaziyatda (radar FOV yaqinida katta tezlik): Qat'iy va buyruq ohangida ("Sekinlashtiring!").
   - Maslahat rejimida: Muloyim va g'amxo'r ("Yomg'ir sababli tormoz masofasi oshgan, ehtiyot bo'ling").
3. Charchoq sezilsa (2 soatdan ortiq to'xtovsiz harakat yoki tunda notekis haydash), yaqin oradagi choyxona yoki yoqilg'i quyish shoxobchasida to'xtashni tavsiya qiling.
4. Hech qachon umumiy yoki cho'zma gaplar ishlatmang.
"""
    }

    private val _decisionEvents = MutableSharedFlow<CoPilotDecision>(extraBufferCapacity = 10)
    val decisionEvents: SharedFlow<CoPilotDecision> = _decisionEvents.asSharedFlow()

    // Ovozli xabarlar to'lib ketishini (Voice spam) oldini oluvchi cooldown taymerlari
    private var lastSpokenRadarId: Long? = null
    private var lastSpokenTimestampMs: Long = 0L
    private val alertCooldownMs = 12000L // Bir xil radar uchun kamida 12 soniya oraliq

    /**
     * Barcha datchiklar, radar va telemetriyani birlashtirib qaror qabul qilish
     */
    suspend fun evaluateSituation(
        threat: RadarThreatAssessment?,
        telemetry: VehicleTelemetry,
        weather: WeatherCondition,
        dayTime: DayTimeSlot,
        roadRoughnessIndex: Double,   // 0.0 (silliq asfalt) .. 1.0 (chuqur o'ydim-chuqur)
        tripDurationMinutes: Int
    ) {
        val now = System.currentTimeMillis()
        val currentSpeed = if (telemetry.speedKmH > 0) telemetry.speedKmH.toDouble() else 0.0

        // -------------------------------------------------------------
        // 1-BOSQICH: HARD REAL-TIME RADAR XAVF ARBITRI (< 5ms)
        // -------------------------------------------------------------
        if (threat != null && threat.threatLevel != ThreatLevel.NONE) {
            val radar = threat.radar

            when (threat.threatLevel) {
                ThreatLevel.CRITICAL_BREACH -> {
                    // Kamera ko'rish maydonida tezlik oshirilgan!
                    val msg = "Diqqat! ${radar.name}. Tezlikni darhol ${radar.speedLimitKmH} ga tushiring!"
                    _decisionEvents.emit(
                        CoPilotDecision(
                            priority = CoPilotDecision.Priority.HIGH,
                            spokenMessageUz = msg,
                            recommendedSpeedKmH = radar.speedLimitKmH,
                            isEmergencyBrakeRequired = true,
                            visualWarningColor = CoPilotDecision.WarningColor.FLASHING_RED,
                            playAlertSound = true
                        )
                    )
                    lastSpokenRadarId = radar.id
                    lastSpokenTimestampMs = now
                    return
                }

                ThreatLevel.WARNING_APPROACHING -> {
                    if (now - lastSpokenTimestampMs > alertCooldownMs || lastSpokenRadarId != radar.id) {
                        val distanceRound = (threat.distanceToRadarMeters / 50).toInt() * 50
                        val msg = "${distanceRound} metrdan so'ng radar. Cheklov ${radar.speedLimitKmH}."
                        _decisionEvents.emit(
                            CoPilotDecision(
                                priority = CoPilotDecision.Priority.MEDIUM,
                                spokenMessageUz = msg,
                                recommendedSpeedKmH = radar.speedLimitKmH,
                                isEmergencyBrakeRequired = false,
                                visualWarningColor = CoPilotDecision.WarningColor.RED,
                                playAlertSound = true
                            )
                        )
                        lastSpokenRadarId = radar.id
                        lastSpokenTimestampMs = now
                        return
                    }
                }

                ThreatLevel.INFO_APPROACHING -> {
                    // Agar haydovchi qoidani buzmayotgan bo'lsa, ovozsiz yashil/sariq bildirishnoma
                    _decisionEvents.emit(
                        CoPilotDecision(
                            priority = CoPilotDecision.Priority.LOW,
                            spokenMessageUz = "",
                            recommendedSpeedKmH = radar.speedLimitKmH,
                            isEmergencyBrakeRequired = false,
                            visualWarningColor = CoPilotDecision.WarningColor.YELLOW,
                            playAlertSound = false
                        )
                    )
                }
                else -> {}
            }
        }

        // -------------------------------------------------------------
        // 2-BOSQICH: PROACTIVE AI MASLAHATCHI (Kontekstual tahlil)
        // -------------------------------------------------------------
        if (now - lastSpokenTimestampMs > 30000L) { // Kamida 30 soniyada 1 ta kontekstual maslahat
            val proactiveAdvice = generateContextualAdvice(
                speed = currentSpeed,
                weather = weather,
                dayTime = dayTime,
                roadRoughness = roadRoughnessIndex,
                tripDurationMinutes = tripDurationMinutes
            )

            if (proactiveAdvice != null) {
                _decisionEvents.emit(proactiveAdvice)
                lastSpokenTimestampMs = now
            }
        }
    }

    /**
     * Yo'l sifati, charchoq va ob-havoga asoslangan intellektual xulosa
     */
    private fun generateContextualAdvice(
        speed: Double,
        weather: WeatherCondition,
        dayTime: DayTimeSlot,
        roadRoughness: Double,
        tripDurationMinutes: Int
    ): CoPilotDecision? {
        // 1. Charchoq tahlili
        if (tripDurationMinutes >= 150) { // 2.5 soat uzluksiz haydash
            return CoPilotDecision(
                priority = CoPilotDecision.Priority.ADVISORY,
                spokenMessageUz = "Uzluksiz 2 yarim soatdan beri harakatdasiiz. Yaqin oradagi kafeda 10 daqiqa dam olishingizni tavsiya qilaman.",
                recommendedSpeedKmH = speed.toInt(),
                isEmergencyBrakeRequired = false,
                visualWarningColor = CoPilotDecision.WarningColor.YELLOW,
                playAlertSound = false
            )
        }

        // 2. Yomon yo'l qoplamasi va yuqori tezlik
        if (roadRoughness > 0.65 && speed > 70.0) {
            return CoPilotDecision(
                priority = CoPilotDecision.Priority.ADVISORY,
                spokenMessageUz = "Yo'l qoplamasida chuqurliklar bor. Podveskani asrash uchun tezlikni kamaytiring.",
                recommendedSpeedKmH = 60,
                isEmergencyBrakeRequired = false,
                visualWarningColor = CoPilotDecision.WarningColor.YELLOW,
                playAlertSound = false
            )
        }

        // 3. Ob-havo xavfi (qora muzlama yoki kuchli tuman)
        if (weather == WeatherCondition.SNOW_OR_ICE && speed > 50.0) {
            return CoPilotDecision(
                priority = CoPilotDecision.Priority.HIGH,
                spokenMessageUz = "Yo'lda muzlama ehtimoli yuqori. Keskin tormozlanishdan saqlaning.",
                recommendedSpeedKmH = 45,
                isEmergencyBrakeRequired = false,
                visualWarningColor = CoPilotDecision.WarningColor.RED,
                playAlertSound = true
            )
        }

        return null
    }

    /**
     * LLM (Gemini / Claude / On-device SLM) ga yuborish uchun Prompt formati
     */
    fun buildLlmContextPrompt(
        telemetry: VehicleTelemetry,
        radar: RadarPoint?,
        distanceMeters: Double?,
        weather: WeatherCondition,
        userVoiceQuery: String
    ): String {
        return """
${SYSTEM_PROMPT_UZBEK}

JORIY HOLAT:
- Haydovchi tezligi: ${telemetry.speedKmH} km/soat
- Dvigatel aylanishi: ${telemetry.rpm} RPM
- Yaqin radar: ${radar?.name ?: "Mavjud emas"} (Cheklov: ${radar?.speedLimitKmH ?: "Noma'lum"})
- Radargacha masofa: ${distanceMeters?.toInt() ?: "N/A"} metr
- Ob-havo: ${weather.name}
- Haydovchi so'rovi: "$userVoiceQuery"

Javobingizni o'zbek tilida, qisqa va aniq tarzda bering:
""".trimIndent()
    }
}

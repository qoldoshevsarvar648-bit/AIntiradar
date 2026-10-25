package uz.aintiradar.core.geometry

import kotlin.math.*

/**
 * Radar otish rejimi (O'zbekistondagi kameralar xususiyati):
 * - INCOMING: Avtomobil peshonasidan (oldidan) suratga oluvchi
 * - OUTGOING: Avtomobil orqa nomeridan (ketidan) suratga oluvchi
 * - BIDIRECTIONAL: Ikkala yo'nalishni ham ushlovchi radar
 */
enum class ShootingMode {
    INCOMING,
    OUTGOING,
    BIDIRECTIONAL
}

/**
 * Radar xavf darajalari
 */
enum class ThreatLevel {
    NONE,                   // Radar yo'nalishi bizga tegishli emas (qarama-qarshi yoki chetda)
    INFO_APPROACHING,       // 500-1000m masofada, tezlik me'yorda
    WARNING_APPROACHING,    // 300-500m masofada, tezlik ruxsat etilgandan oshgan
    CRITICAL_BREACH,        // Radar ko'rish konusi (FOV) ichida va tezlik oshirilgan!
    CAMERA_PASSED           // Radar zonasidan xavfsiz o'tildi
}

/**
 * Radar modeli
 */
data class RadarPoint(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val speedLimitKmH: Int,
    val azimuthDegrees: Double,   // 0°..360° (0 = Shimol, 90 = Sharq)
    val fovAngleDegrees: Double = 40.0, // Kamera ko'rish burchagi
    val rangeMeters: Double = 120.0,     // Radar qamrab oluvchi masofa (masalan, 120 metr)
    val shootingMode: ShootingMode = ShootingMode.INCOMING
)

/**
 * Foydalanuvchi telemetriyasi
 */
data class VehicleState(
    val latitude: Double,
    val longitude: Double,
    val speedKmH: Double,
    val headingDegrees: Double,   // Harakat yo'nalishi (0°..360°)
    val accuracyMeters: Float = 5.0f
)

/**
 * Geometrik tahlil natijasi
 */
data class RadarThreatAssessment(
    val radar: RadarPoint,
    val distanceToRadarMeters: Double,
    val distanceToFovConeMeters: Double,
    val isInFovCone: Boolean,
    val isFacingRadarTrajectory: Boolean,
    val threatLevel: ThreatLevel,
    val speedDeltaKmH: Double,          // speed - limit (musbat bo'lsa qoidabuzarlik)
    val timeToImpactSeconds: Double?   // FOV ga yetib kelish vaqti
)

/**
 * Radar FOV Geometrik Hisoblash Dvigateli
 * Yuqori aniqlikdagi va xotirani tejovchi (allocation-free hot path) matematik modul.
 */
object RadarGeometryEngine {

    private const val EARTH_RADIUS_METERS = 6371000.0
    private const val SPEED_TOLERANCE_UZB_KMH = 5.0 // O'zbekiston QDT bo'yicha +5 km/soat chegarasi

    /**
     * Ikki koordinata orasidagi masofani hisoblash (Haversine formulasi)
     */
    fun calculateDistanceMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2.0).pow(2.0) +
                cos(rLat1) * cos(rLat2) * sin(dLon / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Nuqta A dan Nuqta B ga bo'lgan boshlang'ich azimut burchagi (Bearing) [0..360)
     */
    fun calculateBearingDegrees(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double
    ): Double {
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val dLon = Math.toRadians(toLon - fromLon)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val initialBearing = Math.toDegrees(atan2(y, x))
        return (initialBearing + 360.0) % 360.0
    }

    /**
     * Ikki burchak orasidagi minimal farq (-180°..+180°)
     */
    fun angularDifferenceDegrees(angle1: Double, angle2: Double): Double {
        var diff = (angle1 - angle2) % 360.0
        if (diff > 180.0) diff -= 360.0
        if (diff < -180.0) diff += 360.0
        return diff
    }

    /**
     * Avtomobil radar FOV (ko'rish konusi) ichida joylashganligini tekshirish.
     *
     * Radar azimuti (A_radar) - radar obyektivi qaragan yo'nalish.
     * Radardan mashinaga bo'lgan vektor (Bearing_R_to_C) agar radar azimutidan
     * |diff| <= FOV / 2 oraliqda bo'lsa va masofa <= rangeMeters bo'lsa, mashina konus ichida!
     */
    fun isPointInsideFovCone(
        radarLat: Double, radarLon: Double,
        radarAzimuth: Double, fovAngle: Double, rangeMeters: Double,
        pointLat: Double, pointLon: Double
    ): Boolean {
        val distance = calculateDistanceMeters(radarLat, radarLon, pointLat, pointLon)
        if (distance > rangeMeters) return false

        val bearingFromRadarToPoint = calculateBearingDegrees(radarLat, radarLon, pointLat, pointLon)
        val angleDiff = abs(angularDifferenceDegrees(bearingFromRadarToPoint, radarAzimuth))

        return angleDiff <= (fovAngle / 2.0)
    }

    /**
     * Avtomobil radar traektoriyasiga mos harakatlanayotganini aniqlash.
     * Agar radar INCOMING bo'lsa, avtomobil radarga qarab (peshonama-peshona) kelayotgan bo'lishi kerak.
     * Ya'ni: CarHeading va RadarAzimuth o'rtasidagi farq ~ 180° bo'ladi (qarama-qarshi vektorlar).
     */
    fun isVehicleHeadingTowardsRadar(
        vehicleHeading: Double,
        radarAzimuth: Double,
        shootingMode: ShootingMode
    ): Boolean {
        return when (shootingMode) {
            ShootingMode.INCOMING -> {
                // Kamera avtomobil kelayotgan tomonga qarab turibdi
                val relativeHeading = abs(angularDifferenceDegrees(vehicleHeading, radarAzimuth))
                relativeHeading in 135.0..225.0 // ~180° farq (peshonama-peshona harakat)
            }
            ShootingMode.OUTGOING -> {
                // Kamera avtomobil bilan bir yo'nalishda qaraydi va ketidan suratga oladi
                val relativeHeading = abs(angularDifferenceDegrees(vehicleHeading, radarAzimuth))
                relativeHeading <= 45.0 // ~0° farq (bir xil yo'nalish)
            }
            ShootingMode.BIDIRECTIONAL -> {
                val rel = abs(angularDifferenceDegrees(vehicleHeading, radarAzimuth))
                rel <= 45.0 || rel in 135.0..225.0
            }
        }
    }

    /**
     * Kompleks xavf tahlili (Har bir radar uchun real vaqtda baholash)
     */
    fun assessThreat(
        vehicle: VehicleState,
        radar: RadarPoint
    ): RadarThreatAssessment {
        val distance = calculateDistanceMeters(
            vehicle.latitude, vehicle.longitude,
            radar.latitude, radar.longitude
        )

        val isHeadingMatch = isVehicleHeadingTowardsRadar(
            vehicle.headingDegrees,
            radar.azimuthDegrees,
            radar.shootingMode
        )

        // Agar avtomobil boshqa yo'nalishda ketayotgan bo'lsa (qarama-qarshi yo'lak yoki ko'ndalang)
        if (!isHeadingMatch && distance > radar.rangeMeters) {
            return RadarThreatAssessment(
                radar = radar,
                distanceToRadarMeters = distance,
                distanceToFovConeMeters = max(0.0, distance - radar.rangeMeters),
                isInFovCone = false,
                isFacingRadarTrajectory = false,
                threatLevel = ThreatLevel.NONE,
                speedDeltaKmH = vehicle.speedKmH - (radar.speedLimitKmH + SPEED_TOLERANCE_UZB_KMH),
                timeToImpactSeconds = null
            )
        }

        // Radar FOV konusi ichidalik tekshiruvi
        val inFov = isPointInsideFovCone(
            radarLat = radar.latitude,
            radarLon = radar.longitude,
            radarAzimuth = radar.azimuthDegrees,
            fovAngle = radar.fovAngleDegrees,
            rangeMeters = radar.rangeMeters,
            pointLat = vehicle.latitude,
            pointLon = vehicle.longitude
        )

        val speedDelta = vehicle.speedKmH - (radar.speedLimitKmH + SPEED_TOLERANCE_UZB_KMH)
        val isSpeeding = speedDelta > 0.0

        val speedMps = vehicle.speedKmH / 3.6
        val distanceToCone = max(0.0, distance - radar.rangeMeters)
        val tti = if (speedMps > 1.0) distanceToCone / speedMps else null

        val threat = when {
            inFov && isSpeeding -> ThreatLevel.CRITICAL_BREACH
            inFov && !isSpeeding -> ThreatLevel.INFO_APPROACHING
            distance <= 450.0 && isSpeeding && isHeadingMatch -> ThreatLevel.WARNING_APPROACHING
            distance <= 800.0 && isHeadingMatch -> ThreatLevel.INFO_APPROACHING
            else -> ThreatLevel.NONE
        }

        return RadarThreatAssessment(
            radar = radar,
            distanceToRadarMeters = distance,
            distanceToFovConeMeters = distanceToCone,
            isInFovCone = inFov,
            isFacingRadarTrajectory = isHeadingMatch,
            threatLevel = threat,
            speedDeltaKmH = speedDelta,
            timeToImpactSeconds = tti
        )
    }

    /**
     * Mapbox/MapLibre uchun dinamik FOV Konus poligonining koordinatalarini hisoblash
     * (Visual qatlamda konus chizish uchun)
     */
    fun computeFovPolygonGeoJson(
        radar: RadarPoint,
        steps: Int = 16
    ): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        // 1-nuqta: Radar joylashuvi (cho'qqi)
        points.add(Pair(radar.latitude, radar.longitude))

        val halfFov = radar.fovAngleDegrees / 2.0
        val startAngle = radar.azimuthDegrees - halfFov
        val endAngle = radar.azimuthDegrees + halfFov
        val angleStep = (endAngle - startAngle) / steps

        val rLat = Math.toRadians(radar.latitude)
        val rLon = Math.toRadians(radar.longitude)
        val distRatio = radar.rangeMeters / EARTH_RADIUS_METERS

        for (i in 0..steps) {
            val bearingRad = Math.toRadians(startAngle + (i * angleStep))
            val latRad = asin(sin(rLat) * cos(distRatio) + cos(rLat) * sin(distRatio) * cos(bearingRad))
            val lonRad = rLon + atan2(sin(bearingRad) * sin(distRatio) * cos(rLat), cos(distRatio) - sin(rLat) * sin(latRad))
            points.add(Pair(Math.toDegrees(latRad), Math.toDegrees(lonRad)))
        }

        // Konusni yopish uchun yana radarga qaytamiz
        points.add(Pair(radar.latitude, radar.longitude))
        return points
    }
}

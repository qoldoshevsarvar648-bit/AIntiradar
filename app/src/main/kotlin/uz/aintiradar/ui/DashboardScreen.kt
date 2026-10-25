package uz.aintiradar.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * AIntiRADAR Premium Cyber-HUD Dashboard (Jetpack Compose)
 *
 * Haydovchi uchun qulay, tungi haydashda ko'zni charchatmaydigan,
 * barcha muhim ko'rsatkichlarni bir ekranda ko'rsatuvchi interfeys.
 */
@Composable
fun DashboardScreen(
    currentSpeedKmH: Int = 78,
    speedLimitKmH: Int = 70,
    obdRpm: Int = 2450,
    coolantTemp: Int = 88,
    isObdConnected: Boolean = true,
    activeThreatDistanceM: Int? = 320,
    activeThreatName: String? = "AutoCon - Bunyodkor shoh ko'chasi",
    aiVoiceMessage: String = "Oldinda 320 metrda AutoCon kamerasi. Tezlikni 70 ga tushiring.",
    onReportHazardClick: (String) -> Unit = {},
    onVoiceCoPilotClick: () -> Unit = {}
) {
    val isOverSpeed = currentSpeedKmH > (speedLimitKmH + 5)

    // Pulsatsiyalanuvchi ogohlantirish animatsiyasi
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090D14))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            // 1. TOP BAR: OBD-II & Datchiklar holati
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF131A26))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DirectionsCar,
                        contentDescription = "OBD",
                        tint = if (isObdConnected) Color(0xFF00E676) else Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isObdConnected) "OBD-II: $obdRpm RPM | $coolantTemp°C" else "OBD O'chiq (GPS)",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.GpsFixed,
                        contentDescription = "GPS",
                        tint = Color(0xFF29B6F6),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Aniqlik: 3m", color = Color(0xFF90CAF9), fontSize = 12.sp)
                }
            }

            // 2. RADAR OGOHLANTIRISH BANNERI (Agar xavf bo'lsa)
            if (activeThreatDistanceM != null) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isOverSpeed) Color(0xFFD50000).copy(alpha = pulseAlpha) else Color(0xFF1B2A3F)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .border(
                            width = 2.dp,
                            color = if (isOverSpeed) Color.Red else Color(0xFF00E676),
                            shape = RoundedCornerShape(16.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Alert",
                                tint = Color.Red,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column {
                            Text(
                                text = "${activeThreatDistanceM}m: $activeThreatName",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = if (isOverSpeed) "TEZLIKNI TUSHIRING! (Cheklov $speedLimitKmH)" else "Tezlik me'yorda ($speedLimitKmH)",
                                color = if (isOverSpeed) Color(0xFFFFEB3B) else Color(0xFF00E676),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // 3. MARKAZIY SPIDOMETR & YO'L CHEKLOV BELGISI
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Katta Raqamli Spidometr
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$currentSpeedKmH",
                        fontSize = 92.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isOverSpeed) Color(0xFFFF1744) else Color.White
                    )
                    Text(
                        text = "KM / SOAT",
                        fontSize = 14.sp,
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold
                    )
                }

                // O'zbekiston QDT Standart Yo'l Belgisi (Oq doira, qizil halqa)
                Box(
                    modifier = Modifier
                        .size(105.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(7.dp, Color(0xFFD50000), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$speedLimitKmH",
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.Black
                    )
                }
            }

            // 4. AI CO-PILOT OVOZLI MASLAHAT BLOQI
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onVoiceCoPilotClick,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF6200EA), Color(0xFF00B0FF))))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "CoPilot",
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Text(
                        text = aiVoiceMessage,
                        color = Color(0xFFE0E0E0),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            // 5. CROWDSOURCING QUICK BUTTONS (1-BOSISH ORQALI BELGILASH)
            Text(
                text = "Yo'l xavfini belgilash (Geotag):",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickReportButton("3 Oyoq", Icons.Default.CameraAlt, Color(0xFFFF9100), Modifier.weight(1f)) {
                    onReportHazardClick("TRIPOD_RADAR")
                }
                QuickReportButton("YPX Posti", Icons.Default.LocalPolice, Color(0xFF2979FF), Modifier.weight(1f)) {
                    onReportHazardClick("YPX_POST_MOBILE")
                }
                QuickReportButton("YTH (Avariya)", Icons.Default.CarCrash, Color(0xFFFF1744), Modifier.weight(1f)) {
                    onReportHazardClick("ACCIDENT")
                }
                QuickReportButton("Chuqur yo'l", Icons.Default.Terrain, Color(0xFF78909C), Modifier.weight(1f)) {
                    onReportHazardClick("ROAD_DEFECT")
                }
            }
        }
    }
}

@Composable
fun QuickReportButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B2332)),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = title, tint = accentColor, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = title, fontSize = 10.sp, color = Color.White, maxLines = 1)
        }
    }
}

# AIntiRADAR - Professional Uzbekistan Smart Anti-Radar & AI Co-Pilot Architecture

## 1. Tizim Arxitekturasi va Ma'lumotlar Oqimi (Data Pipelines)

```mermaid
flowchart TB
    subgraph SENSORS["1. Kiruvchi Telemetriya va Sensorlar Qatlami"]
        GPS["Fused Location Provider<br/>(GPS/GLONASS/Galileo - 1-5Hz)"]
        OBD["ELM327 OBD-II Bluetooth/BLE<br/>(RPM, Real Speed, Throttle, Coolant)"]
        IMU["Akselerometr & Giroskop (Z-axis)<br/>(Yo'l sifati / Roughness Index)"]
        MIC["Mikrofon (Voice Command / STT)<br/>('Hey Co-Pilot' Hotword)"]
    end

    subgraph SYNC["2. Sinxronizatsiya va Mahalliy Kesh"]
        ROOM["Room Database (PostGIS Local Cache)<br/>Radarlar, Poligonlar, FOV Konuslar"]
        CROWD["Crowdsourced Hazards Cache<br/>(3 oyoq radarlar, YPX postlari, TTL)"]
    end

    subgraph PIPELINE["3. Real-Time Qayta Ishlash Dvigatellari"]
        GEO_ENGINE["Radar FOV Geometry Engine<br/>Haversine, Bearing, In-Cone, TTI, Ahead Check"]
        OBD_SVC["ObdBluetoothService (StateFlow)<br/>16Hz CAN-Bus Polling Loop"]
        ROUGHNESS["Road Quality Estimator<br/>RMS Z-Acceleration Variance"]
    end

    subgraph AI_CORE["4. AI Co-Pilot & Safety Orchestrator"]
        SAFETY_ARBITER{"Hard Real-Time Arbiter<br/>(< 5ms Latency)<br/>Speed vs Limit + FOV"}
        LLM_COPILOT["Semantic AI Co-Pilot<br/>(On-device SLM / Gemini API)<br/>Charchoq, Ob-havo, Yo'l maslahatlari"]
    end

    subgraph AUDIO["5. Context-Aware Audio & Visual Qatlami"]
        DUCK_CTRL["Audio Ducking Controller<br/>(Fade down 18% / Fade up)"]
        SSVC["Speed-Sensitive Volume (SSVC)<br/>(Salon shovqinini kompensatsiya qilish)"]
        EXO["Media3 / ExoPlayer Engine<br/>(Pleylistlar: Magistral / Tun / Tirbandlik)"]
        TTS["Uzbek TTS Engine (Piper / Google TTS)<br/>Ovozli ogohlantirishlar"]
        MAPBOX["Mapbox / MapLibre Satellite View<br/>Dinamik FOV konus va pulsatsiya"]
    end

    GPS --> GEO_ENGINE
    ROOM --> GEO_ENGINE
    OBD --> OBD_SVC
    OBD_SVC --> SAFETY_ARBITER
    OBD_SVC --> SSVC
    IMU --> ROUGHNESS
    ROUGHNESS --> LLM_COPILOT

    GEO_ENGINE --> SAFETY_ARBITER
    CROWD --> SAFETY_ARBITER

    SAFETY_ARBITER -->|Kritik xavf / Breaching| DUCK_CTRL
    SAFETY_ARBITER -->|Alarm ovozi & Matn| TTS
    SAFETY_ARBITER -->|Qizil pulsatsiyalanuvchi konus| MAPBOX

    LLM_COPILOT -->|Kontekstual maslahat| DUCK_CTRL
    LLM_COPILOT -->|Tavsiya nutqi| TTS
    MIC --> LLM_COPILOT

    DUCK_CTRL --> EXO
    SSVC --> EXO
```

---

## 2. Asosiy Quvurlar (Pipelines) Ishlash Mexanizmi

1. **Geolocation & Radar Vision Pipeline:**
   - Har bir GPS kadrida ($1-5\text{ Hz}$) transport vositasining koordinatasi va harakat yo'nalishi (`heading`) olinadi.
   - Avtomobilning $2000\text{ m}$ radiusidagi barcha faol radarlar keshdan olinadi.
   - Har bir radar uchun `isPointInsideFovCone` va `isVehicleHeadingTowardsRadar` funksiyalari orqali avtomobil radarning ko'rish konusiga kirayotganligi yoki ichidaligi aniqlanadi.
   - Agar $V_{car} > V_{limit} + 5\text{ km/h}$ bo'lsa, xavf darajasi zudlik bilan `CRITICAL_BREACH` yoki `WARNING_APPROACHING` ga o'zgaradi.

2. **OBD-II CAN-Bus Telemetriya Pipeline:**
   - `ObdBluetoothService` ELM327 orqali $16\text{ Hz}$ chastotada dvigatel aylanishlari (RPM), spidometrning real tezligi, gaz pedali holatini o'qiydi.
   - GPS drayveri tunnel, ko'prik osti yoki daraxtzorlarda yo'qolganda ham OBD-II tezligi orqali hisoblashlar to'xtovsiz davom etadi.

3. **Audio Ducking va SSVC Pipeline:**
   - Radar yoki AI Co-Pilot ogohlantirishi boshlanganda musiqa ovozi $300\text{ ms}$ ichida $18\%$ ga pasaytiriladi (`setDuckingEnabled(true)`).
   - Ovozli xabar tugashi bilan musiqa avvalgi darajasiga silliq qaytariladi.
   - Tezlik $0\text{ km/soat}$ dan $120\text{ km/soat}$ gacha ko'tarilganda salon ichidagi shovqinni yengish uchun umumiy musiqa ovozi dinamik ravishda $25\%$ gacha oshiriladi.

const express = require('express');
const cors = require('cors');
const fs = require('fs');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3000;

// Katta hajmdagi rasm (Base64) yuklashni qo'llab-quvvatlash uchun 25mb limit
app.use(cors());
app.use(express.json({ limit: '25mb' }));
app.use(express.urlencoded({ extended: true, limit: '25mb' }));

// Ma'lumotlar saqlanadigan JSON fayl yo'li
const DATA_DIR = path.join(__dirname, 'data');
const DB_FILE = path.join(DATA_DIR, 'db.json');

if (!fs.existsSync(DATA_DIR)) {
    fs.mkdirSync(DATA_DIR, { recursive: true });
}

let db = {
    hazards: [],       // Yo'l xavflari (3 oyoq radar, YPX posti, YTH, chuqur yo'l...)
    radarReviews: {},  // Radar sharhlari { [radarId]: [ { type, comment, image, trustScore, timestamp, user } ] }
    radarScores: {}    // Har bir radar uchun umumiy ball va statistika { [radarId]: { score: 4.8, up: 10, down: 0, mulyaj: 1, total: 11 } }
};

// Bazani fayldan yuklash
function loadDb() {
    try {
        if (fs.existsSync(DB_FILE)) {
            const raw = fs.readFileSync(DB_FILE, 'utf8');
            db = Object.assign(db, JSON.parse(raw));
        }
    } catch (err) {
        console.error("Baza o'qishda xatolik:", err);
    }
}

// Bazani faylga saqlash
function saveDb() {
    try {
        fs.writeFileSync(DB_FILE, JSON.stringify(db, null, 2), 'utf8');
    } catch (err) {
        console.error("Baza saqlashda xatolik:", err);
    }
}

loadDb();

// -------------------------------------------------------------
// 1. HEALTH CHECK
// -------------------------------------------------------------
app.get('/api/health', (req, res) => {
    res.json({
        status: 'ok',
        app: 'AIntiRADAR Global Cloud Backend',
        hazardsCount: db.hazards.length,
        reviewedRadarsCount: Object.keys(db.radarReviews).length,
        timestamp: new Date().toISOString()
    });
});

// -------------------------------------------------------------
// 2. YO'L XAVFLARI (HAZARDS / BELGILAR) CROWDSOURCING
// -------------------------------------------------------------
// Faol xavflarni olish (TTL: 4 soatdan oshmaganlar)
app.get('/api/hazards', (req, res) => {
    const now = Date.now();
    // 4 soatdan oshgan yoki 3 tadan ko'p manfiy ovoz olib ishonchlilik 2.0 dan tushib ketganlarni filtrlash
    db.hazards = db.hazards.filter(h => {
        const age = now - (h.createdAt || 0);
        return age < (4 * 3600 * 1000) && (h.trustScore === undefined || h.trustScore >= 2.0);
    });
    res.json({ success: true, count: db.hazards.length, hazards: db.hazards });
});

// Yangi yo'l xavfi belgisini qoldirish
app.post('/api/hazards', (req, res) => {
    const { type, name, lat, lon, heading, reporterId, description, radarType, radarModel, limit, azimuth, image, isUserRadar, icon } = req.body;
    if (!lat || !lon || !name) {
        return res.status(400).json({ success: false, message: 'Koordinatalar va nom talab qilinadi' });
    }

    const newHazard = {
        id: 'hz_' + Date.now() + '_' + Math.random().toString(36).substring(2, 7),
        type: type || 'ROAD_HAZARD',
        name: name,
        radarType: radarType || '',
        radarModel: radarModel || '',
        limit: limit ? parseInt(limit) : 70,
        lat: parseFloat(lat),
        lon: parseFloat(lon),
        heading: heading !== undefined ? parseFloat(heading) : (azimuth !== undefined ? parseFloat(azimuth) : 0),
        azimuth: azimuth !== undefined ? parseFloat(azimuth) : (heading !== undefined ? parseFloat(heading) : 0),
        description: description || '',
        image: image || null,
        isUserRadar: !!isUserRadar,
        icon: icon || '3leg_camera.png',
        reporterId: reporterId || 'user_' + Math.random().toString(36).substring(2, 7),
        trustScore: 5.0, // Boshlang'ich maksimal ball
        upvotes: 1,
        downvotes: 0,
        createdAt: Date.now(),
        updatedAt: Date.now()
    };

    db.hazards.unshift(newHazard);
    saveDb();

    console.log(`[HAZARD] Yangi xavf belgilandi: ${name} (${lat}, ${lon})`);
    res.status(201).json({ success: true, hazard: newHazard });
});

// Yo'l xavfiga ovoz berish (Tasdiqlash: Ha bor / Yo'q)
app.post('/api/hazards/:id/vote', (req, res) => {
    const { id } = req.params;
    const { isPositive } = req.body;

    const hazard = db.hazards.find(h => h.id === id);
    if (!hazard) {
        return res.status(404).json({ success: false, message: 'Xavf topilmadi' });
    }

    if (isPositive) {
        hazard.upvotes = (hazard.upvotes || 0) + 1;
        hazard.trustScore = Math.min(5.0, Number((hazard.trustScore + 0.3).toFixed(1)));
    } else {
        hazard.downvotes = (hazard.downvotes || 0) + 1;
        hazard.trustScore = Math.max(1.0, Number((hazard.trustScore - 0.6).toFixed(1)));
    }
    hazard.updatedAt = Date.now();
    saveDb();

    res.json({ success: true, hazard });
});

// -------------------------------------------------------------
// 3. RADAR VA KAMERALARNING ISHONCHLILIK TIZIMI VA IZOHLAR
// -------------------------------------------------------------
// Barcha radarlar bo'yicha hisoblangan ballar va so'nggi sharhlar
app.get('/api/radars/reviews', (req, res) => {
    res.json({
        success: true,
        radarScores: db.radarScores,
        reviewsCount: Object.keys(db.radarReviews).length
    });
});

// Muayyan radarga berilgan izohlar ro'yxati
app.get('/api/radars/:id/reviews', (req, res) => {
    const radarId = String(req.params.id);
    const reviews = db.radarReviews[radarId] || [];
    const scoreData = db.radarScores[radarId] || { score: 4.8, up: 0, down: 0, mulyaj: 0, total: 0 };
    res.json({
        success: true,
        radarId,
        scoreData,
        reviews
    });
});

// Radarga 4 ta xulosadan biri + batafsil matn va rasm qo'shish
app.post('/api/radars/:id/reviews', (req, res) => {
    const radarId = String(req.params.id);
    const { verdict, text, image, userId } = req.body;
    // verdict turlari:
    // 1. "radar_bor" (Radar bor)
    // 2. "radar_yoq" (Radar yo'q)
    // 3. "kormadim" (Ko'rmadim / izohsiz)
    // 4. "mulyaj" (Radar bor lekin mulyaj)

    if (!verdict) {
        return res.status(400).json({ success: false, message: 'Xulosa (verdict) ko\'rsatilishi kerak' });
    }

    if (verdict === 'kormadim') {
        // "ko'rmadim" tanlanganda ball o'zgarmaydi va izoh qo'shilmaydi
        return res.json({ success: true, message: 'Izoh qoldirilmadi', radarId });
    }

    if (!db.radarReviews[radarId]) {
        db.radarReviews[radarId] = [];
    }

    const review = {
        id: 'rev_' + Date.now(),
        radarId: radarId,
        verdict: verdict,
        text: text ? String(text).trim() : '',
        image: image || null, // Base64 rasm yoki rasm URL
        userId: userId || 'haydovchi_' + Math.random().toString(36).substring(2, 6),
        timestamp: Date.now()
    };

    db.radarReviews[radarId].unshift(review);
    // Ko'p rasm xotirani to'ldirmasligi uchun har bir radarga oxirgi 15 ta sharhni saqlaymiz
    if (db.radarReviews[radarId].length > 15) {
        db.radarReviews[radarId] = db.radarReviews[radarId].slice(0, 15);
    }

    // Ballik tizimini qayta hisoblash:
    if (!db.radarScores[radarId]) {
        db.radarScores[radarId] = { score: 4.8, up: 5, down: 0, mulyaj: 0, total: 5 };
    }
    const s = db.radarScores[radarId];

    if (verdict === 'radar_bor') {
        s.up = (s.up || 0) + 1;
    } else if (verdict === 'radar_yoq') {
        s.down = (s.down || 0) + 1;
    } else if (verdict === 'mulyaj') {
        s.mulyaj = (s.mulyaj || 0) + 1;
    }
    s.total = (s.up || 0) + (s.down || 0) + (s.mulyaj || 0);

    // Ishonchlilik reytingi: 5 ballik formula
    // "radar_bor" = 5.0, "mulyaj" = 2.5, "radar_yoq" = 1.0
    const weightedSum = (s.up * 5.0) + (s.mulyaj * 2.5) + (s.down * 1.0);
    s.score = Number((weightedSum / s.total).toFixed(1));
    s.isMulyaj = (s.mulyaj > s.up);

    saveDb();

    console.log(`[RADAR REVIEW] Radar #${radarId} uchun xulosa: ${verdict}, Yangi ball: ${s.score}`);
    res.json({
        success: true,
        scoreData: s,
        review: { id: review.id, verdict: review.verdict, text: review.text, hasImage: !!review.image }
    });
});

app.listen(PORT, '0.0.0.0', () => {
    console.log(`====================================================`);
    console.log(`AIntiRADAR Global Cloud Server port ${PORT} da ishga tushdi!`);
    console.log(`Health endpoint: http://localhost:${PORT}/api/health`);
    console.log(`====================================================`);
});

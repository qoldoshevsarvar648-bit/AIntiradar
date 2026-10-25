$ErrorActionPreference = "Stop"

function Get-Dist($lat1, $lon1, $lat2, $lon2) {
    $dy = ($lat2 - $lat1) * 111139.0
    $dx = ($lon2 - $lon1) * 111139.0 * [Math]::Cos($lat1 * [Math]::PI / 180.0)
    return [Math]::Sqrt($dx * $dx + $dy * $dy)
}

function Get-AngleDiff($a1, $a2) {
    $diff = [Math]::Abs($a1 - $a2) % 360
    if ($diff -gt 180) { $diff = 360 - $diff }
    return $diff
}

$allRadars = New-Object 'System.Collections.Generic.List[psobject]'
$idCounter = 1

# 1. OpenStreetMap (export.json) - 493 ta radar
$osmRaw = Get-Content "c:\Users\Lenovo\Desktop\AIntiRADAR\export.json" -Raw -Encoding utf8 | ConvertFrom-Json
foreach ($feat in $osmRaw.features) {
    $lon = [double]$feat.geometry.coordinates[0]
    $lat = [double]$feat.geometry.coordinates[1]
    $limit = 60
    if ($feat.properties.maxspeed) {
        $parsed = 0
        if ([int]::TryParse($feat.properties.maxspeed, [ref]$parsed)) { $limit = $parsed }
    }
    $direction = 0
    if ($feat.properties.direction) {
        $parsedDir = 0
        if ([int]::TryParse($feat.properties.direction, [ref]$parsedDir)) { $direction = $parsedDir }
    }
    $name = if ($feat.properties.name) { $feat.properties.name } else { "AutoCon Radar ($limit km/s)" }

    $allRadars.Add([PSCustomObject]@{
        id = $idCounter++
        name = $name
        lat = [Math]::Round($lat, 6)
        lon = [Math]::Round($lon, 6)
        limit = $limit
        azimuth = $direction
        fov = 22
        range = 280
        source = "OSM"
    })
}
Write-Host "OSM yuklandi: $($allRadars.Count)"

# 2. iGO Primo (Igo.txt) - 289 ta yangi radar (25m dan uzoq)
$igoLines = Get-Content "c:\Users\Lenovo\Desktop\AIntiRADAR\Igo.txt" -Encoding utf8
$igoAdded = 0
foreach ($line in $igoLines) {
    if ($line.StartsWith("X,") -or [string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line.Split(",")
    if ($parts.Length -ge 6) {
        $lon = [double]::Parse($parts[0], [System.Globalization.CultureInfo]::InvariantCulture)
        $lat = [double]::Parse($parts[1], [System.Globalization.CultureInfo]::InvariantCulture)
        $type = [int]::Parse($parts[2])
        $speed = [int]::Parse($parts[3])
        $dir = [int]::Parse($parts[5])

        if ($lat -ge 37.0 -and $lat -le 45.0 -and $lon -ge 56.0 -and $lon -le 73.0) {
            $dup = $false
            foreach ($ex in $allRadars) {
                if ((Get-Dist $lat $lon $ex.lat $ex.lon) -lt 25.0) { $dup = $true; break }
            }
            if (-not $dup) {
                $limit = if ($speed -gt 0) { $speed } else { 60 }
                $name = switch ($type) {
                    1 { "Statsionar Tezlik Radari ($limit km/s)" }
                    2 { "Svetofor Nazorat Kamerasi ($limit km/s)" }
                    3 { "Qizil Chiroq Kamerasi ($limit km/s)" }
                    5 { "Mobil 3-Oyoqli Tripod ($limit km/s)" }
                    10 { "O'rtacha Tezlik Radari ($limit km/s)" }
                    default { "Radar Nazorati ($limit km/s)" }
                }
                $allRadars.Add([PSCustomObject]@{
                    id = $idCounter++
                    name = $name
                    lat = [Math]::Round($lat, 6)
                    lon = [Math]::Round($lon, 6)
                    limit = $limit
                    azimuth = $dir
                    fov = 22
                    range = 280
                    source = "iGO"
                })
                $igoAdded++
            }
        }
    }
}
Write-Host "iGO qoshildi: $igoAdded (Jami: $($allRadars.Count))"

# 3. Navitel (navitel.txt) - 117 ta yangi radar (25m dan uzoq)
$navLines = Get-Content "c:\Users\Lenovo\Desktop\AIntiRADAR\navitel.txt" -Encoding utf8
$navAdded = 0
foreach ($line in $navLines) {
    if ($line.StartsWith("IDX,") -or [string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line.Split(",")
    if ($parts.Length -ge 7) {
        $lon = [double]::Parse($parts[1], [System.Globalization.CultureInfo]::InvariantCulture)
        $lat = [double]::Parse($parts[2], [System.Globalization.CultureInfo]::InvariantCulture)
        $type = [int]::Parse($parts[3])
        $speed = [int]::Parse($parts[4])
        $dir = [int]::Parse($parts[6])

        if ($lat -ge 37.0 -and $lat -le 45.0 -and $lon -ge 56.0 -and $lon -le 73.0) {
            $dup = $false
            foreach ($ex in $allRadars) {
                if ((Get-Dist $lat $lon $ex.lat $ex.lon) -lt 25.0) { $dup = $true; break }
            }
            if (-not $dup) {
                $limit = if ($speed -gt 0) { $speed } else { 60 }
                $name = switch ($type) {
                    100 { "Statsionar Tezlik Radari ($limit km/s)" }
                    101 { "Svetofor va Chiziq Nazorati ($limit km/s)" }
                    102 { "Qizil Chiroq Kamerasi ($limit km/s)" }
                    103 { "O'rtacha Tezlik Nazorati ($limit km/s)" }
                    105 { "Mobil Post ($limit km/s)" }
                    default { "Kamera Nazorati ($limit km/s)" }
                }
                $allRadars.Add([PSCustomObject]@{
                    id = $idCounter++
                    name = $name
                    lat = [Math]::Round($lat, 6)
                    lon = [Math]::Round($lon, 6)
                    limit = $limit
                    azimuth = $dir
                    fov = 22
                    range = 280
                    source = "Navitel"
                })
                $navAdded++
            }
        }
    }
}
Write-Host "Navitel qoshildi: $navAdded (Jami: $($allRadars.Count))"

# 4. CHORRAHA KAMERALARINI ANIQLASH VA 180 GRADUSGA BURISH (40 metr radiusda)
$thresholdMeters = 40.0
$rotatedIds = New-Object 'System.Collections.Generic.HashSet[int]'
$rotatedDetails = @()

for ($i = 0; $i -lt $allRadars.Count; $i++) {
    $r1 = $allRadars[$i]
    for ($j = $i + 1; $j -lt $allRadars.Count; $j++) {
        $r2 = $allRadars[$j]
        $d = Get-Dist $r1.lat $r1.lon $r2.lat $r2.lon
        if ($d -le $thresholdMeters) {
            $diff = Get-AngleDiff $r1.azimuth $r2.azimuth
            if ($diff -lt 90 -and (-not $rotatedIds.Contains($r1.id)) -and (-not $rotatedIds.Contains($r2.id))) {
                $oldAz = $r2.azimuth
                $newAz = ($oldAz + 180) % 360
                $r2.azimuth = $newAz
                [void]$rotatedIds.Add($r2.id)
                $rotatedDetails += [PSCustomObject]@{
                    dist = [Math]::Round($d, 1)
                    r1_id = $r1.id; r1_az = $r1.azimuth; r1_name = $r1.name
                    r2_id = $r2.id; old_az = $oldAz; new_az = $newAz; r2_name = $r2.name
                }
            }
        }
    }
}

Write-Host "Bir-biriga juda yaqin (chorraha) kameralar soni: $($rotatedDetails.Count * 2) ta ($($rotatedDetails.Count) juftlik)"
Write-Host "Ulardan 180 gradusga qarama-qarshi burilgan kameralar soni: $($rotatedIds.Count) ta"

# 5. SOF JAVASCRIPT ARRAY VA JSON SIFATIDA SAQLASH
$jsonString = ConvertTo-Json -InputObject @($allRadars) -Depth 10
# Agar ConvertTo-Json value wrapper qo'shgan bo'lsa, uni tozalash
if ($jsonString.Trim().StartsWith('{"value":') -or $jsonString.Trim().StartsWith('{ "value":')) {
    $parsed = $jsonString | ConvertFrom-Json
    $jsonString = ConvertTo-Json -InputObject @($parsed.value) -Depth 10
}

$jsonPath = "build-standalone\assets\radars.json"
$jsPath = "build-standalone\assets\radars.js"

[System.IO.File]::WriteAllText($jsonPath, $jsonString, [System.Text.Encoding]::UTF8)

$jsString = "window.uzbekistanRadars = " + $jsonString + ";"
[System.IO.File]::WriteAllText($jsPath, $jsString, [System.Text.Encoding]::UTF8)

Write-Host "Yakunlandi: Radarlar soni = $($allRadars.Count), 180 gradusga burilgan = $($rotatedIds.Count)"

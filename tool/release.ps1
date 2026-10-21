# Build the release artifacts and print the upload checklist.
#
#   powershell -ExecutionPolicy Bypass -File tool\release.ps1 -AppVersion 1.0.3
#   powershell -ExecutionPolicy Bypass -File tool\release.ps1 -AppVersion 1.0.3 -Tag
#
# Outputs (gitignored):
#   dist\ToneUpWord.apk  -- FIXED asset name; the client never uses any other name
#   dist\version.json    -- FIXED asset name; fields consumed by data/update/
#
# The tag, versionName and versionCode must agree:
#   tag == v<versionName>
#   versionCode == major*100 + minor*10 + patch
# The app compares versionCode first, so a mismatch silently disables future updates.
#
# Keep this file ASCII-only: Windows PowerShell 5.1 misreads UTF-8 without BOM.

param(
    [Parameter(Mandatory = $true)][string]$AppVersion,
    [switch]$Tag
)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

$gradleFile = 'app/build.gradle.kts'
$gradleText = Get-Content $gradleFile -Raw
$nameMatch = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"')
$codeMatch = [regex]::Match($gradleText, 'versionCode\s*=\s*(\d+)')
if (-not $nameMatch.Success -or -not $codeMatch.Success) {
    throw "Cannot read versionName/versionCode from $gradleFile"
}

$versionName = $nameMatch.Groups[1].Value
$versionCode = [int]$codeMatch.Groups[1].Value

if ($AppVersion -ne $versionName) {
    throw "AppVersion '$AppVersion' != versionName '$versionName' in $gradleFile"
}

$parts = $versionName.Split('.')
if ($parts.Count -ne 3) {
    throw "versionName '$versionName' must be major.minor.patch"
}
$expectedCode = [int]$parts[0] * 100 + [int]$parts[1] * 10 + [int]$parts[2]
if ($versionCode -ne $expectedCode) {
    throw "versionCode $versionCode != major*100+minor*10+patch ($expectedCode)"
}

$tagName = "v$versionName"
if ($Tag) {
    $existing = git tag --list $tagName
    if ($existing) {
        throw "Tag $tagName already exists; bump versionName instead of reusing it."
    }
}

Write-Host "Building release $versionName ($versionCode)..." -ForegroundColor Cyan
& .\gradlew.bat :app:assembleRelease -x lintVitalRelease
if ($LASTEXITCODE -ne 0) {
    throw "Gradle release build failed with exit code $LASTEXITCODE"
}

$signedApk = 'app/build/outputs/apk/release/app-release.apk'
$unsignedApk = 'app/build/outputs/apk/release/app-release-unsigned.apk'
if (-not (Test-Path $signedApk)) {
    if (Test-Path $unsignedApk) {
        throw "Release APK is UNSIGNED. Configure keystore.properties or TONEUP_KEYSTORE_* first (docs/RELEASE_SIGNING.md)."
    }
    throw "Release APK not found at $signedApk"
}

$sha256 = (Get-FileHash $signedApk -Algorithm SHA256).Hash.ToLower()
$size = (Get-Item $signedApk).Length

$notes = ''
if (Test-Path 'release-notes.md') {
    $notes = (Get-Content 'release-notes.md' -Raw -Encoding UTF8).Trim()
}
if ([string]::IsNullOrWhiteSpace($notes)) {
    Write-Warning 'release-notes.md is empty; the update dialog will have no notes.'
}

$dist = Join-Path (Get-Location) 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null

$url = 'https://github.com/hbt123-123/ToneUp_word/releases/latest/download/ToneUpWord.apk'
$manifest = [ordered]@{
    version     = $versionName
    versionCode = $versionCode
    url         = $url
    sha256      = $sha256
    size        = $size
    notes       = $notes
}
$jsonText = $manifest | ConvertTo-Json -Depth 4
[IO.File]::WriteAllText(
    (Join-Path $dist 'version.json'),
    $jsonText,
    (New-Object Text.UTF8Encoding($false))
)
Copy-Item $signedApk (Join-Path $dist 'ToneUpWord.apk') -Force

if ($Tag) {
    git tag $tagName
    Write-Host "Created local tag $tagName (push it with: git push origin $tagName)" -ForegroundColor Yellow
}

Write-Host ''
Write-Host '================ Upload checklist (GitHub -> Releases -> Draft a new release) ================' -ForegroundColor Green
Write-Host "Tag:         $tagName        (must equal v<versionName>; create it if missing)"
Write-Host "Title:       $tagName"
Write-Host "Body:        paste release-notes.md"
Write-Host 'Asset 1:     dist\ToneUpWord.apk    <- the name must be exactly ToneUpWord.apk'
Write-Host 'Asset 2:     dist\version.json      <- the name must be exactly version.json'
Write-Host ''
Write-Host 'Release must be PUBLISHED (drafts and pre-releases are invisible to the app).'
Write-Host "versionCode : $versionCode"
Write-Host "sha256      : $sha256"
Write-Host "size        : $size bytes"

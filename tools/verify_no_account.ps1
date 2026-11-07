$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$source = Join-Path $root 'app/src/main/java/rongyi/lingbai'
foreach ($name in @('AccountSettingsUi.kt', 'LicenseGate.java', 'LicenseManager.kt',
        'LicenseApiClient.kt', 'LicenseRefreshJobService.kt', 'LicenseSessionStore.kt',
        'DeviceIdentity.kt', 'RememberedCredentialStore.kt')) {
    if (Test-Path -LiteralPath (Join-Path $source $name)) { throw "Account source remains: $name" }
}
$forbidden = 'LicenseGate|LicenseManager|LicenseApiClient|LicenseRefresh|AccountSettingsCard|lingbai\.xyz|/api/v1/auth'
foreach ($file in Get-ChildItem -LiteralPath $source -Recurse -File) {
    if ($file.Extension -notin @('.java', '.kt')) { continue }
    if ((Get-Content -LiteralPath $file.FullName -Raw) -match $forbidden) {
        throw "Account reference remains: $($file.FullName)"
    }
}
[xml]$manifest = Get-Content -LiteralPath (Join-Path $root 'app/src/main/AndroidManifest.xml')
$android = 'http://schemas.android.com/apk/res/android'
foreach ($service in $manifest.manifest.application.service) {
    if ($service.GetAttribute('name', $android) -match 'License') { throw 'License service remains' }
}
$permissions = @($manifest.manifest.'uses-permission' | ForEach-Object { $_.GetAttribute('name', $android) })
if ($permissions -contains 'android.permission.RECEIVE_BOOT_COMPLETED') { throw 'Obsolete boot permission remains' }
if ($permissions -notcontains 'android.permission.INTERNET') { throw 'Weather/Hitokoto network permission missing' }
foreach ($file in @('wechatpanel/HomeSidePanelWeather.kt', 'wechatpanel/HomeSidePanelHitokoto.kt')) {
    if (-not (Test-Path -LiteralPath (Join-Path $source $file))) { throw "Retained feature missing: $file" }
}
Write-Output 'PASS: account source, endpoint, UI and service removed; retained network features present.'

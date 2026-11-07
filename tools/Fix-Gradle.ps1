param([switch]$Build)
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$WrapperFile = Join-Path $ProjectRoot 'gradle\wrapper\gradle-wrapper.properties'
$WrapperText = Get-Content -LiteralPath $WrapperFile -Raw
if ($WrapperText -notmatch 'downloads\.gradle\.org/distributions/gradle-9\.7\.1-bin\.zip' -or
    $WrapperText -notmatch 'distributionSha256Sum=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a') {
    throw 'Wrapper URL or official checksum does not match this release. Restore the original wrapper properties.'
}
Write-Host 'Close Android Studio and other Gradle builds before running this repair.'
$GradleCacheRoot = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$RepairPaths = @(
    (Join-Path $GradleCacheRoot 'wrapper\dists\gradle-9.7.1-bin'),
    (Join-Path $ProjectRoot 'app\.cxx'),
    (Join-Path $ProjectRoot 'app\.externalNativeBuild'),
    (Join-Path $ProjectRoot 'app\build\intermediates\cxx')
)
foreach ($RepairPath in $RepairPaths) {
    if (Test-Path -LiteralPath $RepairPath) { Remove-Item -LiteralPath $RepairPath -Recurse -Force }
}
$NowUtc = [DateTime]::UtcNow
Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'app\src\main\cpp') -Recurse -File |
    Where-Object { $_.Name -eq 'CMakeLists.txt' -or $_.Extension -in '.cmake','.c','.h' } |
    ForEach-Object { if ($_.LastWriteTimeUtc -gt $NowUtc) { $_.LastWriteTimeUtc = $NowUtc } }
Push-Location $ProjectRoot
try {
    & .\gradlew.bat --version
    if ($LASTEXITCODE -ne 0) { throw 'Gradle distribution verification failed. Keep the official SHA; do not replace it with the downloaded file hash.' }
    if ($Build) {
        & .\gradlew.bat :app:assembleRelease
        if ($LASTEXITCODE -ne 0) { throw 'Release build failed. See the first compiler or native error above.' }
    }
} finally { Pop-Location }

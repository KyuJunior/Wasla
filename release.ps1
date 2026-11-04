# Builds a signed release APK and publishes it as a GitHub release; the app picks it up via "check for updates".
# Usage: bump versionCode/versionName in app/build.gradle.kts, commit, then:  .\release.ps1 "what changed"
param([string]$Notes = "")
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not (Test-Path keystore.properties)) { throw "keystore.properties missing: the release key is required so updates install over the old version." }
if (git status --porcelain) { throw "Commit your changes first." }

$version = [regex]::Match((Get-Content app/build.gradle.kts -Raw), 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
if (-not $version) { throw "versionName not found in app/build.gradle.kts" }
$tag = "v$version"
if (git tag --list $tag) { throw "$tag already exists: bump versionName and versionCode first." }

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" }
# Gradle fails with "Unable to establish loopback connection" when the temp path has spaces/dots.
New-Item -ItemType Directory -Force C:\gtmp | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\gtmp -Djava.io.tmpdir=C:\gtmp"

.\gradlew.bat testDebugUnitTest assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw "Build failed." }

$apk = "app/build/outputs/apk/release/Wasla-$tag.apk"
Copy-Item app/build/outputs/apk/release/app-release.apk $apk -Force
git tag $tag
git push origin HEAD $tag
gh release create $tag $apk --title "Wasla $version" --notes $Notes

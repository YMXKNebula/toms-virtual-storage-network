param([Parameter(ValueFromRemainingArguments=$true)][string[]]$GradleTasks = @('build'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$previousLocation = Get-Location
$previousJava = $env:JAVA_HOME
$previousGradle = $env:GRADLE_USER_HOME
$previousOptions = $env:JAVA_TOOL_OPTIONS
try {
    Set-Location -LiteralPath $projectRoot
    $dependencies = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'dependencies.json') -Raw | ConvertFrom-Json
    $jdk = Join-Path $PSScriptRoot $dependencies.jdkDirectory
    if (-not (Test-Path -LiteralPath (Join-Path $jdk 'bin\java.exe'))) {
        $archive = Join-Path $PSScriptRoot 'jdk25.zip'
        if (-not (Test-Path -LiteralPath $archive)) { Invoke-WebRequest -Uri $dependencies.jdkUrl -OutFile $archive }
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $dependencies.jdkSha256) { throw 'Java download checksum mismatch.' }
        Expand-Archive -LiteralPath $archive -DestinationPath $PSScriptRoot -Force
    }
    $vendor = Join-Path $projectRoot 'vendor'
    New-Item -ItemType Directory -Path $vendor -Force | Out-Null
    $tomJar = Join-Path $vendor $dependencies.tomJar
    if (-not (Test-Path -LiteralPath $tomJar)) { Invoke-WebRequest -Uri $dependencies.tomUrl -OutFile $tomJar }
    if ((Get-FileHash -LiteralPath $tomJar -Algorithm SHA512).Hash -ne $dependencies.tomSha512) { throw 'Tom''s Storage checksum mismatch; the existing file was preserved.' }
    $clothJar=Join-Path $vendor 'cloth-config-fabric-26.3.159.jar'
    if(-not(Test-Path -LiteralPath $clothJar)){Invoke-WebRequest -Uri 'https://maven.shedaniel.me/me/shedaniel/cloth/cloth-config-fabric/26.3.159/cloth-config-fabric-26.3.159.jar' -OutFile $clothJar}
    if((Get-FileHash -LiteralPath $clothJar -Algorithm SHA256).Hash -ne '1716b9964f90d2b885f9bd3887f8824b71b55c32a4123a5853486e722f7c2e4b'){throw 'Tom compile-only Cloth API checksum mismatch.'}
    $env:JAVA_HOME = $jdk
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-home'
    $env:JAVA_TOOL_OPTIONS = ($previousOptions + ' -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE').Trim()
    & (Join-Path $projectRoot 'gradlew.bat') --console=plain @GradleTasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle exited with code $LASTEXITCODE." }
} finally {
    Set-Location -LiteralPath $previousLocation.Path
    $env:JAVA_HOME = $previousJava
    $env:GRADLE_USER_HOME = $previousGradle
    $env:JAVA_TOOL_OPTIONS = $previousOptions
}

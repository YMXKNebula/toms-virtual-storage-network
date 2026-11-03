param([Parameter(Mandatory=$true)][string]$GameAssets)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$assetRoot=Join-Path $projectRoot '.gradle-home\caches\fabric-loom\assets'
$sourceIndex=Join-Path $GameAssets 'indexes\34.json'
New-Item -ItemType Directory -Path (Join-Path $assetRoot 'indexes') -Force | Out-Null
$targetIndex=Join-Path $assetRoot 'indexes\26.3-34.json'
Copy-Item -LiteralPath $sourceIndex -Destination $targetIndex -Force
$index=Get-Content -LiteralPath $targetIndex -Raw | ConvertFrom-Json -AsHashtable
$seen=[Collections.Generic.HashSet[string]]::new()
$copied=0
foreach($entry in $index.objects.Values){
    $hash=[string]$entry.hash
    if($hash -notmatch '^[a-f0-9]{40}$'){throw 'Invalid asset hash'}
    if(-not $seen.Add($hash)){continue}
    $relative=Join-Path $hash.Substring(0,2) $hash
    $source=Join-Path (Join-Path $GameAssets 'objects') $relative
    $destination=Join-Path (Join-Path $assetRoot 'objects') $relative
    if((Get-FileHash -LiteralPath $source -Algorithm SHA1).Hash -ne $hash){throw 'Installed asset checksum mismatch'}
    New-Item -ItemType Directory -Path (Split-Path $destination) -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination -Force
    $copied++
}
[ordered]@{objects=$copied;localSourceIndexSha1=(Get-FileHash -LiteralPath $targetIndex -Algorithm SHA1).Hash;destination=$assetRoot} | ConvertTo-Json

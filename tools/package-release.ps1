param([string]$Version='1.0.0')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
if($Version -ne [regex]::Match((Get-Content build.gradle -Raw),"version = '([^']+)'").Groups[1].Value){throw 'Build version differs from release version'}
$metaTemplate=Get-Content src/main/resources/fabric.mod.json -Raw|ConvertFrom-Json
$tvsn=$metaTemplate.id -eq 'toms_virtual_storage'
$archive=if($tvsn){'toms-virtual-storage-network-26.3'}else{'toms-storage-additions-26.3'}
$expectedClasses=if($tvsn){101}else{4}
$profiles=if($tvsn){[ordered]@{core=@('build/test-results/test/TEST-junit-jupiter.xml',28);server=@('build/gametest-release-1.0.0-results.xml',55)}}else{[ordered]@{native=@('build/gametest-release-native-results.xml',18);tvsn=@('build/gametest-release-tvsn-results.xml',18);stack128=@('build/gametest-release-stack128-results.xml',18);stack256=@('build/gametest-release-stack256-results.xml',18)}}
$tests=@()
foreach($profile in $profiles.Keys){
    [xml]$xml=Get-Content -LiteralPath $profiles[$profile][0] -Raw
    if($xml.SelectNodes('//testcase').Count -ne $profiles[$profile][1] -or $xml.SelectNodes('//failure|//error|//skipped').Count){throw "Release tests failed or incomplete: $profile"}
    $tests+=[ordered]@{profile=$profile;tests=$xml.SelectNodes('//testcase').Count;failures=0}
}
$names=@("$archive-$Version.jar","$archive-$Version-sources.jar")
$classes=@{}
foreach($name in $names){
    $zip=[IO.Compression.ZipFile]::OpenRead((Join-Path $root ('build/libs/'+$name)))
    try{
        $reader=[IO.StreamReader]::new($zip.GetEntry('fabric.mod.json').Open());try{$meta=$reader.ReadToEnd()|ConvertFrom-Json}finally{$reader.Dispose()}
        $acceptedVersions=if($name.EndsWith('-sources.jar')){@($Version,'${version}')}else{@($Version)}
        if($meta.id -ne $metaTemplate.id -or $meta.version -notin $acceptedVersions -or $meta.environment -ne '*'){throw 'Wrong release metadata'}
        if(-not $zip.GetEntry($meta.icon)){throw 'Mod icon missing'}
        if(-not $tvsn -and $meta.depends.PSObject.Properties.Name -contains 'toms_virtual_storage'){throw 'TVSN must remain optional'}
        if(@($zip.Entries|Where-Object FullName -Match '(^dev/(tpc|tsa)/(integration|clienttest|scale|testlauncher)/|^fabric\.mod\.json\.bak$|\.log$)').Count){throw 'Test code or local files leaked into release'}
        foreach($resource in Get-ChildItem -LiteralPath src/main/resources -File -Recurse){
            $relative=[IO.Path]::GetRelativePath((Join-Path $root 'src/main/resources'),$resource.FullName).Replace('\','/')
            if($relative -eq 'fabric.mod.json'){continue}
            $entry=$zip.GetEntry($relative);if(-not $entry){throw "Resource missing: $relative"}
            $stream=$entry.Open();$sha=[Security.Cryptography.SHA256]::Create();try{$hash=[Convert]::ToHexString($sha.ComputeHash($stream))}finally{$stream.Dispose();$sha.Dispose()}
            if($hash -ne (Get-FileHash -LiteralPath $resource.FullName -Algorithm SHA256).Hash){throw "Resource differs from source: $relative"}
        }
        if($name.EndsWith('-sources.jar')){
            $files=@(Get-ChildItem src/main/java -Filter '*.java' -Recurse -File)
            if(@($zip.Entries|Where-Object FullName -Like '*.java').Count -ne $files.Count){throw 'Source JAR contains unexpected source files'}
            foreach($file in $files){
                $relative=[IO.Path]::GetRelativePath((Join-Path $root 'src/main/java'),$file.FullName).Replace('\','/')
                $entry=$zip.GetEntry($relative);if(-not $entry){throw "Source missing: $relative"}
                $stream=$entry.Open();$sha=[Security.Cryptography.SHA256]::Create();try{$hash=[Convert]::ToHexString($sha.ComputeHash($stream))}finally{$stream.Dispose();$sha.Dispose()}
                if($hash -ne (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash){throw "Source differs: $relative"}
            }
        }else{
            $entries=@($zip.Entries|Where-Object FullName -Like '*.class')
            if($entries.Count -ne $expectedClasses){throw 'Unexpected production class count'}
            foreach($entry in $entries){
                $stream=$entry.Open();$sha=[Security.Cryptography.SHA256]::Create();try{$hash=[Convert]::ToHexString($sha.ComputeHash($stream)).ToLowerInvariant()}finally{$stream.Dispose();$sha.Dispose()}
                if($hash -ne (Get-FileHash -LiteralPath ('build/classes/java/main/'+$entry.FullName) -Algorithm SHA256).Hash.ToLowerInvariant()){throw 'Packaged class differs from tested output'}
                $classes[$entry.FullName]=$hash
            }
        }
    }finally{$zip.Dispose()}
}
$delivery=Join-Path $root ('dist/'+$Version)
New-Item -ItemType Directory -Path $delivery -Force|Out-Null
$assets=@()
foreach($name in $names){
    Copy-Item -LiteralPath ('build/libs/'+$name) -Destination (Join-Path $delivery $name) -Force
    $assets+=[ordered]@{name=$name;sha256=(Get-FileHash -LiteralPath (Join-Path $delivery $name) -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$assets|ForEach-Object { $_.sha256+'  '+$_.name }|Set-Content -LiteralPath (Join-Path $delivery 'SHA256SUMS.txt') -Encoding ascii
$evidence=Join-Path $root ('docs/test-results/'+$Version)
New-Item -ItemType Directory -Path $evidence -Force|Out-Null
[ordered]@{version=$Version;mod=$metaTemplate.id;assets=$assets;productionClassCount=$expectedClasses;classHashes=$classes;classBytesMatchTestedOutput=$true;testCodeIncluded=$false;environment='*';tests=$tests}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $evidence 'verification.json') -Encoding utf8
$assets|ConvertTo-Json

param([string]$JdkHome='C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
if(!(Test-Path -LiteralPath (Join-Path $JdkHome 'include/jni.h'))){throw 'Build JDK requires include/jni.h'}
& cmake -S (Join-Path $root 'native/mfg_agent') -B (Join-Path $root 'build/native/mfg_agent-cmake') -A x64 "-DJDK_HOME=$JdkHome"
if($LASTEXITCODE -ne 0){throw 'Agent configuration failed'}
& cmake --build (Join-Path $root 'build/native/mfg_agent-cmake') --config Release
if($LASTEXITCODE -ne 0){throw 'Agent build failed'}

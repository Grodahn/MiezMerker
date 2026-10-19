[CmdletBinding(PositionalBinding=$false)]
param(
    [string]$IdfPath = $(if ($env:IDF_PATH) { $env:IDF_PATH } else { 'C:\esp\v6.1\esp-idf' }),
    [string]$ToolsPath = $(if ($env:IDF_TOOLS_PATH) { $env:IDF_TOOLS_PATH } else { 'C:\Espressif\tools' }),
    [switch]$Ccache,
    [string]$CcacheDir = $(if ($env:CCACHE_DIR) { $env:CCACHE_DIR } else {
        Join-Path $env:LOCALAPPDATA 'MiezMerker/ccache/esp-idf-6.1' }),
    [Parameter(Position=0, ValueFromRemainingArguments=$true)][string[]]$IdfArguments
)
# Process-local configuration only; the EIM activated terminal is also supported.
$ErrorActionPreference = 'Stop'
# Restore the calling shell too, including on error; no user/machine settings.
$environmentNames = @('PYTHONUTF8', 'IDF_PATH', 'IDF_TOOLS_PATH', 'ESP_IDF_VERSION',
    'IDF_PYTHON_ENV_PATH', 'ESP_ROM_ELF_DIR', 'PATH', 'IDF_CCACHE_ENABLE',
    'CCACHE_DIR', 'CCACHE_MAXSIZE', 'CCACHE_COMPILERCHECK')
$savedEnvironment = @{}
foreach ($name in $environmentNames) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$result = 1
try {
    $env:PYTHONUTF8 = '1'
    $env:IDF_PATH = $IdfPath
    $env:IDF_TOOLS_PATH = $ToolsPath
    $env:ESP_IDF_VERSION = '6.1'
    $env:IDF_PYTHON_ENV_PATH = Join-Path $ToolsPath 'python/v6.1/venv'
    $env:ESP_ROM_ELF_DIR = Join-Path $ToolsPath 'esp-rom-elfs/20241011'
    $toolBins = @('cmake/*/bin', 'ninja/*', 'riscv32-esp-elf/*/riscv32-esp-elf/bin',
        'riscv32-esp-elf-gdb/*/riscv32-esp-elf-gdb/bin')
    foreach ($pattern in $toolBins) {
        $toolBin = Get-Item (Join-Path $ToolsPath $pattern) | Sort-Object FullName | Select-Object -Last 1
        $env:PATH = $toolBin.FullName + ';' + $env:PATH
    }
    $python = Join-Path $env:IDF_PYTHON_ENV_PATH 'Scripts/python.exe'
    $env:PATH = (Split-Path $python) + ';' + $env:PATH
    if ($Ccache) {
        $cacheCommand = Get-Command ccache -CommandType Application -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if (-not $cacheCommand) {
            # EIM may already have installed ccache without exporting it to PATH.
            $cacheCommand = Get-Item (Join-Path $ToolsPath 'ccache/*/ccache-*/ccache.exe') `
                -ErrorAction SilentlyContinue | Sort-Object FullName | Select-Object -Last 1
            if (-not $cacheCommand) {
                throw 'Explicit -Ccache requested, but ccache is missing from PATH and EIM tools. Install it manually or omit -Ccache.'
            }
            $env:PATH = $cacheCommand.DirectoryName + ';' + $env:PATH
        }
        $env:IDF_CCACHE_ENABLE = '1'
        $env:CCACHE_DIR = [IO.Path]::GetFullPath($CcacheDir)
        $env:CCACHE_MAXSIZE = '1G'
        # IDF regenerates picolibc.specs; hash content rather than its fresh mtime.
        $env:CCACHE_COMPILERCHECK = 'content'
    }
    & $python (Join-Path $IdfPath 'tools/idf.py') @IdfArguments
    $result = $LASTEXITCODE
} finally {
    foreach ($name in $environmentNames) {
        if ($null -eq $savedEnvironment[$name]) {
            Remove-Item "Env:$name" -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
        }
    }
}
exit $result

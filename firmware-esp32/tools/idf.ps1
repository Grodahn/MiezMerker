[CmdletBinding(PositionalBinding=$false)]
param(
    [string]$IdfPath = 'C:\esp\v6.1\esp-idf',
    [string]$ToolsPath = 'C:\Espressif\tools',
    [Parameter(Position=0, ValueFromRemainingArguments=$true)][string[]]$IdfArguments
)
# Process-local configuration only; the EIM activated terminal is also supported.
$ErrorActionPreference = 'Stop'
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
& $python (Join-Path $IdfPath 'tools/idf.py') @IdfArguments
exit $LASTEXITCODE

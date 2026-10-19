# Incremental Windows firmware development (#145)

Follow [testing.md](testing.md), stages **0 → A → R → B → C**. This is the
local ESP-IDF 6.1 workflow; [#144](https://github.com/Grodahn/MiezMerker/issues/144)
already owns the GitHub compiler cache. No firmware, security or CI gate changes.

## Install, activate, verify

Use an EIM-activated PowerShell, or the existing
`firmware-esp32/tools/idf.ps1`. The wrapper accepts `-IdfPath`/`-ToolsPath`,
defaults to the corresponding EIM environment variables, then its established
installation paths. Nothing is installed automatically or written to user/machine
PATH. In an activated terminal `idf.py` may be an alias for `Invoke-idfpy`.

First check the installed tools. EIM may already provide ccache; the wrapper's
`-Ccache` also finds it under the selected tools directory. Otherwise, optionally
install it yourself with `winget install --id Ccache.Ccache --exact`, or manually
unpack an official Windows release and add its directory to **this process's** PATH.
Open a new terminal after a WinGet installation if discovery still fails.

```powershell
Get-Command ccache
ccache --version
$env:IDF_CCACHE_ENABLE = '1'
$env:CCACHE_DIR = Join-Path $env:LOCALAPPDATA 'MiezMerker/ccache/esp-idf-6.1'
$env:CCACHE_MAXSIZE = '1G'
$env:CCACHE_COMPILERCHECK = 'content'
ccache -s
```

Set these in the **agent's actual process**, not just another terminal. This
cache persists on this Windows host; a cloud agent or another user does not
inherit it. Keep the cache outside Git, restrict write access to trusted local
users, and never share it with untrusted builders. Compiler flags/configuration
remain part of ccache's key. `content` also hashes IDF's regenerated
`picolibc.specs` by content; do not weaken keys with `sloppiness` or ignored flags.
The 1 GiB bound can evict entries; it is not a promised hit rate.

Alternatively use `-Ccache` on each wrapper invocation. It sets the four cache
variables above for the child IDF process and restores the caller's environment
afterward. `-CcacheDir <absolute-path>` overrides the directory. Explicit opt-in
without ccache fails before IDF; omit the switch for ordinary existing behavior
(including an inherited `IDF_CCACHE_ENABLE`). No cache is reset or deleted.

## Smallest useful iteration

Configure the host build once, then build only the affected target and use the
compact runner. Discover real test/target names before choosing a selection:

```powershell
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --target board-adapter-test --parallel
python scripts/test.py firmware --test '^esp32-board-adapters$'
# For auth changes, also use the separate existing offline-auth host build:
cmake -S firmware-esp32/tests -B build/offline-auth -DCMAKE_BUILD_TYPE=Release
cmake --build build/offline-auth --parallel
python scripts/test.py firmware --build-dir build/offline-auth --label auth
```

Select the actual changed contract: core, adapters, claim, offline credentials,
crypto or security-clock consumers. Preserve their assertions and coverage;
an empty selection is an error. Do not run unrelated backend/frontend suites
locally for a wrapper-only change. Review the whole issue diff and security
boundaries once before the affected-domain completion selection. After a repair,
rerun its failing test first, then the directly affected group.

For board integration reuse a **correctly configured** profile. From the repo root:

```powershell
$idf = Join-Path $PWD 'firmware-esp32/tools/idf.ps1'
Push-Location firmware-esp32/idf
$dev = @('-B','build-dev','-D','SDKCONFIG=sdkconfig.local-dev','-D',
  'SDKCONFIG_DEFAULTS=sdkconfig.defaults;sdkconfig.dev','-D','MM_DEVELOPMENT_BUILD=ON')
$production = @('-B','build-production','-D','SDKCONFIG=sdkconfig.local-production','-D',
  'SDKCONFIG_DEFAULTS=sdkconfig.defaults','-D','MM_DEVELOPMENT_BUILD=OFF')
& $idf -Ccache -IdfArguments ($dev + @('app'))
if ($LASTEXITCODE -ne 0) { throw 'Development app build failed' }
# Only when production integration is affected:
& $idf -Ccache -IdfArguments ($production + @('app'))
if ($LASTEXITCODE -ne 0) { throw 'Production app build failed' }
Pop-Location
```

`app` builds the application incrementally, not the bootloader/full image set.
A fresh directory must first be configured and compiles its application cold;
it is not a warm-build benchmark. The project already pins ESP32-C3. Do not
repeat `set-target`, dependency setup, or `fullclean` during iteration. Never run
two builds against the same build directory concurrently.

Always retain separate build directories **and** SDKCONFIG files. Defaults only
seed unset values; they do not erase stale dev settings. The existing
`MM_DEVELOPMENT_BUILD=OFF` guard must remain FATAL for development inputs in
production. Never copy objects/build directories between profiles. A compiler
cache may be shared because compiler/configuration inputs are hashed; generated
objects and sdkconfig files may not. The optional field-alpha policy has its own
directory/configuration and remains a separate CI variant.

Use `build size` in place of `app` for a justified local image gate: configuration,
partition, bootloader/toolchain changes, size risk, or preparing a hardware image.
For an application-only change, focused host tests plus necessary incremental
integration before PR are enough locally; the final full image gate is CI.
After review fixes, rerun affected host tests and only the incremental profiles
whose compiler coverage is needed. No automated flashing, erase, NVS reset or
cache cleaning belongs in this loop.

## Audit and timings without redundant full builds

Run the wrapper contract tests without an ESP SDK:

```powershell
python -m unittest discover -s firmware-esp32/tools -p test_idf_wrapper.py -v
# Also verify Windows PowerShell 5.1:
$env:IDF_TEST_POWERSHELL = 'powershell'
python -m unittest discover -s firmware-esp32/tools -p test_idf_wrapper.py -v
Remove-Item Env:IDF_TEST_POWERSHELL
```

For an actual build, set the cache variables as above in the measuring terminal,
then record version, directory, host/CPU, revision, profile, exact arguments,
SDKCONFIG and before/after statistics. For each **needed** invocation (inside the
IDF directory with `$idf`/`$dev`/`$production` from above):

```powershell
ccache --version
ccache -s > cache-before.txt
$timer = [Diagnostics.Stopwatch]::StartNew()
& $idf -Ccache -IdfArguments ($dev + @('app')) *> build-timing.log
$buildExit = $LASTEXITCODE
$timer.Stop()
ccache -s > cache-after.txt
"seconds=$($timer.Elapsed.TotalSeconds); exit=$buildExit"
if ($buildExit -ne 0) { throw 'Build failed; inspect build-timing.log' }
```

Keep full logs locally (e.g. under ignored `test-results/`); show only duration,
status and hit/miss deltas. A no-op `app` build can make **zero compiler calls**;
that is incremental reuse, not a 100% ccache hit rate. Cache hits accelerate
compilation, not dependency resolution, CMake configure, Ninja startup, linking,
image creation or I/O. Separate these using IDF/Ninja log phases; wall time alone
does not measure each phase. A genuine cold/warm full-build comparison requires
identical inputs and separately recorded cache state. Perform that comparison
only when full builds are already justified; do not clear a useful build/cache
or repeat full images solely to produce a speed claim. Record unmeasured cases
explicitly. See [local measurements](benchmarks/issue-145.md).

Troubleshooting: check `Get-Command ccache`, the wrapper's selected EIM paths,
`ccache --show-config`, the real `CCACHE_DIR`, and
`build-dev/CMakeCache.txt` (`CCACHE_ENABLE`/`CCACHE_FOUND`). IDF should print
`ccache will be used for faster recompilation`. If switching cache enablement,
IDF reconfigures incrementally; no `fullclean` is necessary. A previously cached
missing compiler/cache path warrants a targeted `reconfigure` with explicit
profile arguments. Preserve errors/exit codes and inspect the first failing phase.

## Final gate

On the exact final PR head, require the existing complete GitHub CI: full
production, dev **and field-alpha** `build size`, partition/size checks, config
assertions, dev-only mock-symbol exclusion, intentional release-guard rejection,
host/offline credential/claim/security-clock coverage, PostgreSQL and browser E2E.
No `app`-only replacement, skipped gate, weaker profile, or CI change is authorized
by local optimization. Verify final head SHA and checks, then stop without extra
full builds or post-green polling. Do not merge automatically.

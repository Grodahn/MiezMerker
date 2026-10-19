# Windows build measurement status (#145)

Measured on 2026-10-19 in the local Windows Codex environment, not a Linux
cloud runner. Source base: `1ad6058` plus the #145 wrapper changes. Processor
reported by the process: Intel64 Family 6 Model 142 Stepping 10, 8 logical
processors; full CPU model/CIM inventory was unavailable in the sandbox.

- Installed ESP-IDF: 6.1.0, EIM Python 3.12.10.
- ccache selected on PATH: **4.14.1** (the existing EIM installation also has
  4.12.1; no installation was performed).
- CMake 4.0.3, Ninja 1.12.1; compiler identification GNU 15.2.0.
- Separate new `build-dev` / `sdkconfig.local-dev`, defaults
  `sdkconfig.defaults;sdkconfig.dev`, `MM_DEVELOPMENT_BUILD=ON`.
- Dedicated initially empty cache under ignored `test-results/ccache-145`,
  `CCACHE_MAXSIZE=1G`, `CCACHE_COMPILERCHECK=content`, `IDF_CCACHE_ENABLE=1`.

| Requested measurement | Result |
| --- | --- |
| Cold dev `build size` | Configure failed after **49.007 s**, IDF exit **2**; no image build completed |
| Warm full dev / production full builds | Not executed |
| Repeated `app`, including one source timestamp change | Not executed after configure failure |
| Cache before/after | Both show 0.0 GB / 1.0 GB, no compiler hit/miss counts |
| Speedup / hit rate | **Unmeasured**, no percentage inferred from an empty cache |

IDF logged `ccache will be used for faster recompilation` and detected the
compiler. The component manager was disabled to prevent automatic dependency
installation. The already installed, lock-hash-matching cJSON source was copied
into this isolated checkout; with the manager disabled, IDF did not register
`espressif__cjson` and stopped at configuration. Enabling dependency resolution
was rejected by automatic approval review because it could download dependencies,
contrary to the task's no-installation constraint. No bypass, cleaning, flashing,
dependency download or repeated full compilation followed. An earlier sandbox
attempt was interrupted without usable output; it is not a timed build result.

The 49.007 s is a **failed configure wall time**, not a cold-build baseline or a
compiler benchmark. CMake/compiler detection and process startup occurred;
Ninja compilation, linking, size reporting and cache reuse were not reached.
No isolated dependency/I/O timings or production numbers are available. The
unchanged final GitHub matrix validates full images but does not establish
Windows performance. A representative unrestricted Windows EIM host with its
dependencies already resolved must supply cold/warm per-profile data when those
image builds are otherwise needed; use the
[measurement procedure](../windows-firmware-development.md#audit-and-timings-without-redundant-full-builds).

Local verification: four wrapper contract tests passed under both PowerShell 7
and Windows PowerShell 5.1. Existing flash-safety and build-ID tests passed
across focused runs. Initial build-ID failures were test-environment issues
(missing Ninja PATH and a temporary archive nested inside this checkout); adding
the installed Ninja and setting Git's ceiling at the temporary directory fixed
the affected tests without modifying their expectations.

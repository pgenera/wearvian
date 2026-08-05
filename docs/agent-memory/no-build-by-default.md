---
name: no-build-by-default
description: "Don't build/send APKs by default — only when the user explicitly asks"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 1a773a26-8818-4ca0-87c2-81d3e14bc129
---

Do NOT build an APK (assembleDebug/assembleRelease) or send it by default. Only build a full APK when the user **explicitly asks** for a build/APK. To verify a change in the meantime, **compile just the affected file/module** (e.g. `compileDebugKotlin`), not a full APK build.

**Why (confirmed 2026-06-08):** the user controls when they test on-device; unsolicited APK builds are noise AND the box is RAM-constrained — R8 release builds are the memory-hungry step and the user sometimes has no spare RAM (they'll say so / ask me to stop). Full builds can take minutes. Current heap/daemon tuning is in [[gradle-daemon-config]].

**How to apply:** after committing watch code, stop at compile verification (single file/module). Build an APK only when they say "build", "apk", "give me a build", etc. Before kicking off a release/R8 build, be mindful of host RAM; if they've said RAM is tight, don't build.

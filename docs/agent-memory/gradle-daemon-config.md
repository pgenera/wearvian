---
name: gradle-daemon-config
description: "Why wearvian's gradle.properties is tuned the way it is (resident daemon, 3 GiB heap, shared across both projects)"
metadata: 
  node_type: memory
  type: project
  originSessionId: b194ce1f-01a8-4332-84ac-f74059335d3e
---

Both `watch/wear/gradle.properties` and `companion/gradle.properties` are tuned for this **~14 GiB / no-swap / 4-core** host (see [[toolchain-setup]]):

- `org.gradle.daemon=true` with `org.gradle.daemon.idletimeout=3600000` (1 h) — resident for warm rebuilds, dropped after an hour.
- `org.gradle.jvmargs=-Xmx6144m` — **identical in both projects on purpose** so they share ONE daemon. 6 GiB gives the memory-hungry R8 release step generous headroom; ~6.5 GiB resident still leaves ~7 GiB for the OS.
- `org.gradle.parallel=true` — build independent projects/tasks across the 4 cores.
- `kotlin.compiler.execution.strategy=in-process` — keeps Kotlin in the (resident, warm) Gradle daemon; RAM now allows a separate KotlinCompileDaemon if ever wanted, but in-process keeps it to one JVM.

**Why:** originally `daemon=false` + small heaps on a 7.3 GiB box (2026-06-15); the machine was then upgraded to ~14 GiB / 4 cores (2026-06-16) and the user asked to make use of it → bumped heap 3→6 GiB and added parallel. If both projects' `jvmargs` ever diverge they'll spawn two daemons — keep them identical.

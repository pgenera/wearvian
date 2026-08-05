---
name: ref-rivian-decompile-archive
description: Persistent Rivian Android app decompile archive at /home/pgenera/wearvian/rivian-re (clean Java + tools + README)
metadata: 
  node_type: memory
  type: reference
  originSessionId: 1a773a26-8818-4ca0-87c2-81d3e14bc129
---

The reverse-engineering decompile of the official Rivian Android app (`com.rivian.android.consumer`) is persisted at **`/home/pgenera/wearvian/rivian-re/`** (outside any git repo — it's Rivian's proprietary code, do NOT commit). Contents: `base.apk`, `java-classes3/` (jadx clean Java of classes3.dex = the BLE lib + `com.rivian.android.vehicle.session`), `java-mini/` (em/h60/q60/pv/s60/l60/jh subset incl. `em/f0`), `tools/` (jadx.zip, baksmali.jar, smali.jar), and `README.md` with the low-RAM pipeline + a key-class map.

This box has little free RAM (full-app jadx OOMs / crashed it twice). Use baksmali for grep-able smali, and the **mini-dex trick** (assemble a package subset with smali.jar → `jadx -Xmx512m mini.dex`) for clean Java of specific classes. Derived protocol facts are in `wearvian/docs/passive-entry-protocol.md`. See [[active-commands-are-encrypted]] and [[passive-entry-is-a-session]]. (The earlier working copy under `~/.claude/jobs/1a773a26/tmp/` may be cleaned up; this archive is the durable one.)

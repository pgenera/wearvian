---
name: battery-baseline-passive-day
description: "On-watch battery measurement (2026-07-14, passive-all-day); numbers, where the drain goes, and the planned active-day comparison"
metadata: 
  node_type: memory
  type: project
  originSessionId: 2d0d6551-1d56-4c56-89da-f3457f77f90c
---

**Baseline battery run — 2026-07-14, Pixel Watch 4, key PASSIVE all day** (measured via `adb dumpsys batterystats --charged`; app was vCode 1053/1055, versionName 0.9.8):

- **~17.3 mAh/h** average discharge: **197 mAh drained over 11h 25m** on battery, of ~**279 mAh** est. capacity → roughly **~16h** projected runtime under this load.
- **Bluetooth is the whole story:** **scan time 9h 56m = ~87% of the day** (near-continuous passive proximity scan). **75% of drain is screen-off/doze** (147 of 197 mAh); screen-on was only ~12m / 50 mAh.
- **The app itself is lean:** wearvian's DIRECT per-UID attribution (u0a200) was just **1.45 mAh** (cpu 0.99 + a 14m50s fgs wakelock). The cost is charged to the **Bluetooth stack** (UID 1002 / global scan) doing OUR scanning — no leak or runaway wakelock in app code.
- Conclusion: efficient per-CPU; the ~17 mAh/h is inherent to keeping BLE scanning ~87% of the day for passive presence. The only lever is scan **duty cycle / uptime**, not app efficiency — see the scan-duty-cycle levers (back off to the autoConnect wake after a confirmed long departure; per-mode radio duty is fixed by the OS, so registered-scan uptime is what we control). Ties to [[parked-nearby-power-already-optimal]] and [[parked-idle-power-save]].

**Planned follow-up — 2026-07-15: repeat the SAME measurement with the key NOT passive all day** (active/normal use) to compare active vs passive drain. Caveat when comparing: tomorrow's run will be on **vCode 1056**, which includes the **PK-session flap fix** ([[pk-session-flap-fix]]) — the old passive-day build still had the connect→up→5s-timeout→reconnect churn, so some of the 17 mAh/h baseline was flap waste, not pure passive scanning. Interpret the delta as (active vs passive) AND (flap-fixed vs not), not active-vs-passive alone.

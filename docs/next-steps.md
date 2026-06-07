# wearvian — next steps (as of 2026-06-07, v0.4.1)

The watch app (`main`, versionCode 1008) is a working offline phone key, **confirmed
on-vehicle**: passive entry + drive, lock/unlock, frunk + liftgate, live lock/closure
status, the redesigned tile, and the M2 proximity active→passive→wake→active cycle all
work on the R1S. What's left is packaging, a couple of small features, and two parked
protocol unknowns.

## Done since v0.2.5 (was "blocked on you" / backlog — now landed)
- **M2 proximity wake — confirmed on-vehicle.** Full active→passive→wake→active cycle
  works: after idle the service goes passive (wake lock released, BLE torn down) and the
  hardware-offloaded scan restarts the link on approach without an FGS-start block. See
  `docs/proximity-wake.md`.
- **F5 live lock/closure status — done.** The `0x1c` plaintext status stream is decoded
  (lock, doors, windows, frunk, liftgate — byte map in `docs/passive-entry-protocol.md`)
  and drives the UI icon state.
- **Liftgate (hatch) open/close** confirmed on-vehicle (`0x2a`/`0x2b`).
- **Watch tile redesign** — hex layout (key in center + six icon controls: lock/unlock,
  frunk open/close, hatch open/close), vehicle-state button shading, debounced live
  refresh, screen-relative sizing, and a tile-chooser preview image.
- **Last-known status when inactive** — closure/lock affordances persist in memory and
  render dimmed (gray vs. white) when the key is inactive; RAM-only, never written to disk,
  gone on process death.
- **PK reconnect** — the dormant phone-key module now falls back to `autoConnect` after a
  direct-connect timeout, fixing the "PK timeout" reconnect storm.

## Blocked on you (testing / accounts)
1. **Play Store** — once the developer account exists: create the upload keystore
   (`keytool`), fill `keystore.properties` in both repos, `:app:bundleRelease` each, upload
   the **phone** + **Wear** AABs to one listing with Play App Signing. See
   `docs/play-store-packaging.md`.

## Code I can pick up (mostly unblocked)
2. **Windows / charge-port over BLE** — request codes (`0x15`/`0x16`, `0x36`/`0x37`) are
   byte-exact with the official app and answered with a `17 01` ack, but the vehicle doesn't
   actuate on our one-shot connection. The 2026-06-06 sleeping-truck capture shows the app
   sends these through a **live presence session** (sustained heartbeat/ranging) using the
   running **csn** command counter, not an isolated connect. Fold command-sending into the
   `PresenceService` heartbeat loop (dedicated 0-based `commandCounter`). Re-hidden in the UI
   until this works. Details in `docs/passive-entry-protocol.md`.
3. **Panic command** — add it (`0x07`/`0x34`) with a confirmation dialog (the one control
   that needs a confirm before firing).
4. **Deep-sleep BLE wake (retry, carefully)** — a *separate, opt-in* path (don't touch the
   proven command flow). Fix the regression cause: continue the command counter past the
   heartbeat count instead of resetting to 0. Needs a genuine deep-sleep repro. (Reverted
   attempt: commit 73a5218.)
5. **Monetization architecture** (only if pursuing a paid tier) — keep **free = offline**
   with the clean no-INTERNET manifest; deliver **paid cloud features via a dynamic feature
   module** that adds INTERNET on purchase, so the free privacy story stays intact.

## Parked / known limitations
- **Tile cold-start has no state shading.** The last-known status is RAM-only, so a tile
  rendered after the process is killed shows no shading until the service is alive again.
  Accepted trade-off of the no-disk decision.
- **SoC, range, and cabin temp ARE on BLE** (decoded 2026-06-07 from the `0x1c` stream —
  `[5]`=SoC %, `[7]`=cabin °C, `[8..9]`=range km; see `docs/passive-entry-protocol.md`). The
  parser exposes them; a UI readout is an easy follow-up. **Still cloud-only:** charge limit and
  the climate **setpoint** (not found in the frame — needs a capture that changes it to isolate
  the byte).

## Housekeeping
- Delete the merged feature branches (`wearvian-m1-phone-key`, `wearvian-m2-proximity-wake`,
  `wearvian-watch-lock`) once `main` is confirmed canonical.
- `PLAN.md` is the original M1 design and is now historical (M1 is complete); the live
  protocol reference is `docs/passive-entry-protocol.md`.

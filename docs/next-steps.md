# wearvian — next steps (as of v0.5.3, versionCode 1013)

The watch app is a working offline phone key, confirmed on-vehicle on the R1S: passive entry +
drive, lock/unlock, all closures, climate precondition, panic, live status (lock/closures + SoC,
range, cabin temp, charge state/ETA), the tile, always-on power-saving passive mode, and
remove-from-wrist anti-theft. What's left is on-vehicle verification of newer additions, the Play
release loop, and a couple of parked unknowns.

## Needs on-vehicle verification
- **R1T (truck) support.** Tailgate UI + the `OPEN_TAILGATE` command (0x24) and the rear-closure
  status bit are derived from the protocol notes but **untested on a truck** (we only have an R1S).
  The debug Settings "Force R1T" switch exercises the UI on the R1S. Verify command + status when an
  R1T is available. See [[active-commands-are-encrypted]] and `passive-entry-protocol.md`.
- **Enroll-as-watch (companion).** The companion `wearvian-enroll-as-watch` branch passes
  `deviceType="watch"` to the cloud `EnrollPhone` (with a login-screen checkbox to fall back to
  "phone"). It is **unconfirmed** the cloud accepts `"watch"` — validate on the next re-enrollment.

## Play Store
- Single listing, two AABs (watch + companion) uploaded to the **same release**; the app must be
  opted into the **Wear OS form factor** or the watch build shows "unavailable on Google Play". Both
  AABs must be signed with the real upload key (not the debug key). See `play-store-packaging.md`.

## Parked / known limitations
- **Deep-sleep BLE wake.** There is no BLE wake command; presence is the wake. An earlier warm-up
  attempt regressed awake-vehicle commands and was reverted — revisit only as a separate, opt-in
  path that continues the command counter past the heartbeat count, and only with a genuine
  deep-sleep repro.
- **Cloud-only fields.** Charge **limit %** and the climate **setpoint** are not in the `0x1c` frame
  (the UI shows charge time-to-limit without the target %). Capturing a frame before/after changing
  the setpoint would be needed to isolate it, if ever wanted.
- **Tile cold-start has no state shading.** Last-known status is RAM-only (no disk), so a tile
  rendered after the process is killed shows no shading until the service is alive again — an
  accepted trade-off of the no-disk decision.

## Housekeeping
- Delete merged feature branches once `main` is confirmed canonical
  (`wearvian-m1-phone-key`, `wearvian-m2-proximity-wake`, `wearvian-watch-lock`, `wearvian-r1t-support`).

---
name: watch-vs-phone-enrollment
description: Enrolling with keyDeviceSubtype=WATCH makes the car skip passive lock/unlock (drive still works); enables a big watch-mode battery win
metadata: 
  node_type: memory
  type: project
  originSessionId: b194ce1f-01a8-4332-84ac-f74059335d3e
---

The companion's `enrollAsWatch` boolean (default true) controls the Rivian `EnrollPhone` call:
`asWatch=true` → sends `keyDeviceSubtype="WATCH"` + `source="MOBILE"` (`type` stays `"phone"` either
way); `asWatch=false` → omits them. **CONFIRMED on-vehicle 2026-06-17:** the server honours
`keyDeviceSubtype="WATCH"` — the key shows up as a *watch* in the vehicle, and the car does **NO
passive lock/unlock** for it, though **drive-enable still works**. (`companion/.../net/RivianCloud.kt`.)

Plan (user wants BOTH modes):
- **phone-enrolled** (`asWatch=false`) → full proximity (passive unlock/lock + drive) = current behavior.
- **watch-enrolled** (`asWatch=true`) → manual lock/unlock via app/tile (active commands) + presence
  ONLY around the drive window.

Why it matters for battery: a watch key needs no proximity unlock/lock, so the approach/departure
heartbeat stream is wasted — and dropping it is SAFE (the blocker for every earlier wake-lock idea was
"don't break walk-away auto-lock," which doesn't exist for a watch key). So presence (wake lock +
heartbeats on all 5 links) need only run briefly to enable drive (then driving-doze takes over) → the
wake lock is held almost never. This is the big wake-lock reduction we couldn't find before.

Status (2026-06-17): plumbing SHIPPED in 0.6.5 (companion relays `asWatch`; watch persists it in
`EnrollmentStore`/`Enrollment.asWatch`, defaults false=phone for old installs). The watch-mode presence
LIFECYCLE is built on branch `wearvian-watch-mode` (not merged): doze by default (connection+0x1c, no
heartbeats/wake-lock) + burst presence on triggers (door-open / vehicle-wake / manual command), 3-min
inactivity timeout, drop on car-sleep, never in-gear. UNVALIDATED on-vehicle. Requires a FRESH
enrollment as a watch (asWatch=true) to activate — existing enrollments default to phone.

Original plumbing gap (now closed): the watch did NOT know its enrollment type — `EnrollmentContract.Result`/
`VehicleResult` (watch side) has no device-type field; the companion has `enrollAsWatch` but doesn't
relay it over the Data Layer. Step 1 of the feature is plumbing that through. Wake-lock mechanics
(send-vs-receive) and doze: see [[vehicle-status-0x1c-decode]], [[passive-entry-is-a-session]],
[[companion-app-data-layer-contract]], [[drive-gated-on-signed-params]].

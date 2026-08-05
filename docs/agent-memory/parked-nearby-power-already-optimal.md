---
name: parked-nearby-power-already-optimal
description: "Parked-nearby power can't be cut with the driving-doze trick — ranging needs the wake lock"
metadata: 
  node_type: memory
  type: project
  originSessionId: e533784a-1d88-4acf-8f1d-58490cfb0c28
---

Concluded 2026-07-04 (user accepted): there is NO further power optimization to squeeze from the
"parked nearby" state using the driving-doze technique. Don't re-litigate this.

**Why:** driving-doze (`enterDriving`) releases the wake lock + pauses heartbeats while KEEPING the
GATT connection + 0x1c (FGS is NOT stopped — only `onDestroy` calls stopForeground). The wake-lock
savings come *from* pausing the 300 ms ranging heartbeats — and ranging IS passive entry (walk-up
lock/unlock/drive). It's free to drop while driving (ranging pointless then); parked-nearby, dropping
it kills walk-up-unlock. You can't keep ranging without the wake lock (300 ms beats need the CPU awake).

**Per key type:**
- Phone key: the ~15-min parked-active window (`monitorStableIdle`) pays the wake lock deliberately to
  keep walk-up-unlock alive after parking; dozing would kill it.
- Watch key (`asWatch` — how the user enrolls): the car does no passive entry, so ranging parked is
  already useless AND watch-mode already dozes like driving-doze (`monitorWatchPresence` pauses
  heartbeats/no wake lock; `monitorStableIdle`/`monitorDriving` `return` early for watchMode). So the
  wake-lock win is ALREADY applied parked-nearby. The only lever left is dropping the maintained
  connection → full passive (offloaded scan), which trades the door-open drive-enable trigger + live
  0x1c status for marginally lower power — judged not worth it.

See [[watch-vs-phone-enrollment]], [[parked-idle-power-save]], [[passive-entry-is-a-session]],
[[m2-proximity-wake]].

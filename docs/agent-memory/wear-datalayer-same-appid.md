---
name: wear-datalayer-same-appid
description: Wear Data Layer delivers messages only between apps with the SAME applicationId (+ signature)
metadata: 
  node_type: memory
  type: project
  originSessionId: 1a773a26-8818-4ca0-87c2-81d3e14bc129
---

The Wear OS Data Layer (`MessageClient`/`DataClient`) routes a message to the app on the other node whose **AppKey = (applicationId, signing cert)** matches the sender. **Same signing cert is NOT sufficient — the `applicationId` (package) must also match.** Different package names → GMS logs `WearableService: Failed to deliver message to AppKey[...]` and the listener never fires.

**Why:** The wearvian watch app (`applicationId org.fivesevenfive.wearvian`) sent `/wearvian/enroll/request` to the phone; the companion had `applicationId org.fivesevenfive.wearvian.companion`, so GMS on the phone couldn't find a matching app and dropped it. Capability discovery (`android_wear_capabilities`) was also empty over the cloud relay (`isNearby=false`).

**How to apply:** The companion phone app must use `applicationId = "org.fivesevenfive.wearvian"` (matching the watch) even though its code `namespace` stays `org.fivesevenfive.wearvian.companion`. The standard Android Studio Wear template shares applicationId between the `:mobile` and `:wear` modules for exactly this reason. See [[companion-app-data-layer-contract]].

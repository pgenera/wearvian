---
name: ref-the-mace-rivian-cloud-api
description: "the-mace/rivian-python-api — cloud Rivian client; relevant to M4 cloud features, NOT the BLE drive/heartbeat work"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 1a773a26-8818-4ca0-87c2-81d3e14bc129
---

https://github.com/the-mace/rivian-python-api — another Python Rivian client. Per the user (2026-06-02) it is **cloud-service oriented** (account/vehicle-state/cloud commands via GraphQL), **not BLE**. So it does **not** help the current gating unknown (the on-vehicle BLE presence/heartbeat protocol needed for drive — see [[passive-entry-is-a-session]]). File it as a reference for **M4 cloud features** (battery %, cabin temp, cloud `sendVehicleCommand`), to be done in the companion app, alongside the primary BLE reference `bretterer/rivian-python-client`.

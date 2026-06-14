# wearvian — project history

This file is historical context, not a description of the current build. For the live picture see
`docs/passive-entry-protocol.md` (the reverse-engineered BLE protocol — source of truth),
`docs/proximity-wake.md`, `docs/wearvian-overview.md`, and `docs/next-steps.md`.

## Milestone 1 (the original goal)

Build a standalone Wear OS app for a Pixel Watch 4 that acts as a Rivian phone key for a Gen-1 R1S
using the watch's own BLE radio, fully offline once set up, surviving the user's phone being
destroyed. M1 is **complete and confirmed on-vehicle** (passive entry + drive, lock/unlock, frunk,
liftgate, live status).

The pivotal early insight (still true): **there is no discrete "drive" command.** Once a key is
enrolled (a one-time cloud step) and maintains authenticated BLE presence, the vehicle's
passive-entry logic unlocks and enables drive automatically by localizing the key.

## Two big course corrections since the original design

The original M1 plan made two assumptions that reverse-engineering later overturned. They're
recorded here so the obsolete approaches aren't re-attempted:

1. **Enrollment: standalone auth-server + QR/nonce handoff → companion phone app.** The first design
   used a local/App-Engine Flask broker (`auth-server/`) and a QR + browser login, with the watch
   polling for tokens over IP (the watch had `INTERNET`). **Removed.** Enrollment is now done by the
   [`wearvian-companion`](https://github.com/pgenera/wearvian-companion) phone app over the Wear OS
   Data Layer; the watch has **no `INTERNET` permission**. Contract:
   `docs/companion-enrollment-protocol.md`.

2. **Presence: OS bonding (`createBond`) → an authenticated heartbeat session.** The first design
   assumed the watch had to BLE-*bond* with the vehicle and advertise under a bonded identity.
   A btsnoop of the official app on a never-paired device showed there is **no link-layer pairing or
   bonding at all** — presence is an in-the-clear GATT session streaming an RSSI-carrying,
   key-authenticated heartbeat. Our early `createBond()` attempts were the source of the HCI 0x05
   failures. See `docs/passive-entry-protocol.md`.

## Protocol constants (early reference)

These were captured at the start from community references; the authoritative, on-vehicle-verified
versions (plus the full active-command table, heartbeat frame, and `0x1c` status map) are in
`docs/passive-entry-protocol.md`.

- Vehicle BLE peripheral local name: `Rivian Phone Key`
- Service UUID (Active Entry): `52495356-454e-534f-5253-455256494345`
- Crypto: keypair = secp256r1 (NIST P-256); shared secret = ECDH; derive via HKDF-SHA256
  (length 32, salt=None, info=""); sign via HMAC-SHA256. Active-command frames add AES-128-GCM.
- GraphQL gateway: `https://rivian.com/api/gql/gateway/graphql`; enrollment `publicKey` is the
  X9.62 uncompressed point as hex (not PEM).

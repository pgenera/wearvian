# Companion ↔ watch enrollment protocol (Wear OS Data Layer)

> Kept in the watch repo. The companion app implements this contract on its side
> (`comms`/`wear` packages), but the protocol is documented here, not in the companion repo.

The companion **phone** app performs the one-time / periodic (~monthly) Rivian cloud enrollment
that cannot be done offline, then hands the results to the **watch** over the Wear OS Data Layer
(`MessageClient` / `DataClient` / `CapabilityClient`). Watch side: `comms/EnrollmentContract.kt`
+ `comms/CompanionEnrollmentClient.kt`.

## Key custody (non-negotiable)

The **watch generates its own EC keypair** (secp256r1, Android Keystore) and its **private key
never leaves the watch**. The companion phone receives only the watch's **public key** (X9.62
uncompressed point, hex) and submits that to Rivian `EnrollPhone`. The phone never generates or
holds the watch's private key.

## Capabilities

- Phone advertises capability: `wearvian_companion_enrollment`
- Watch advertises capability: `wearvian_watch`

Each side uses `CapabilityClient` to discover a connected node advertising the other's capability
before sending.

## Messages

### `/wearvian/enroll/request` — watch → phone

UTF-8 JSON:

```json
{
  "v": 1,
  "requestId": "<uuid>",
  "publicKey": "<X9.62 uncompressed EC point, hex>",
  "deviceName": "Pixel Watch 4",
  "deviceType": "watch"
}
```

`publicKey` is the watch's Keystore public key in the exact encoding Rivian `EnrollPhone` expects
(uncompressed hex, not PEM). `deviceType` is what the cloud records for the key; the companion may
pass it through (`"watch"`) or override to `"phone"` (see the enroll-as-watch work).

### `/wearvian/enroll/result` — phone → watch

UTF-8 JSON:

```json
{
  "v": 1,
  "requestId": "<echoed from request>",
  "status": "ok",
  "vehicles": [
    {
      "vehicleId": "...",
      "vin": "...",
      "vasVehicleId": "...",
      "vehiclePublicKey": "<hex>",
      "vasPhoneId": "...",
      "identityId": "..."
    }
  ],
  "userId": "...",
  "sessionTokens": {
    "csrfToken": "...",
    "appSessionToken": "...",
    "userSessionToken": "..."
  }
}
```

On failure:

```json
{ "v": 1, "requestId": "<echoed>", "status": "error", "error": "<human-readable>" }
```

For multi-vehicle accounts the phone returns one entry per vehicle in `vehicles`.

## Transport notes

- Primary transport is `MessageClient` for liveness/acknowledgement.
- If a payload exceeds the ~100 KB `MessageClient` limit (session tokens can be large), fall back
  to `DataClient` (`PutDataRequest`) at the same path; the watch listens on both.

## Flow

1. Watch enrolls → sends `/wearvian/enroll/request` to the phone node advertising
   `wearvian_companion_enrollment`.
2. Phone performs Rivian cloud login / MFA / `getUserInfo` / `EnrollPhone` (submitting the watch's
   public key).
3. Phone replies on `/wearvian/enroll/result`.
4. Watch persists the enrollment and operates **fully offline indefinitely** for passive
   unlock/drive; the phone is only needed again for the next re-auth / re-enroll.

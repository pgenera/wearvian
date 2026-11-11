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

### Result — phone → watch

The success payload is UTF-8 JSON:

```json
{
  "v": 1,
  "requestId": "<echoed from request>",
  "status": "ok",
  "asWatch": false,
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
  "userId": "..."
}
```

Rivian session tokens are no longer included (the watch never used them, and the payload is now
stored in the Data Layer). Older watches ignore the missing field.

On failure (message only):

```json
{ "v": 1, "requestId": "<echoed>", "status": "error", "error": "<human-readable>" }
```

The phone enrolls one vehicle per request; if the account has several that can take the key, the
user picks one on the phone. If the key is already enrolled on that vehicle as the requested kind
(phone or watch), the phone reuses the existing `vasPhoneId`/`identityId` without calling
`EnrollPhone`.

The result is delivered two ways:

- **DataItem at `/wearvian/enroll/result/<requestId>`** (durable), with the JSON bytes under the
  DataMap key `payload`. The Data Layer stores it and syncs it whenever the watch is reachable, so
  a finished enrollment survives the watch timing out, leaving the screen, or being killed. The
  watch persists its outstanding request ids (24 h) and, when not enrolled, looks for a stored
  result for one of them on every resume.
- **Message on `/wearvian/enroll/result`** (best-effort, for watches before durable results, and
  for errors).

### `/wearvian/enroll/ack` — watch → phone

`{ "v": 1, "requestId": "<id>" }`, sent after the watch saved the enrollment. The watch also
deletes the result DataItem(s) itself; on the ack the phone deletes its copy and shows the
enrollment as confirmed. Until then the phone shows "waiting for your watch to confirm".

## Flow

1. Watch enrolls → sends `/wearvian/enroll/request` to the phone node advertising
   `wearvian_companion_enrollment`.
2. Phone performs Rivian cloud login / MFA / `getUserInfo` / `EnrollPhone` (submitting the watch's
   public key).
3. Phone stores the result DataItem and sends the result message.
4. Watch persists the enrollment, deletes the DataItem and acks. It then operates **fully offline indefinitely** for passive
   unlock/drive; the phone is only needed again for the next re-auth / re-enroll.

# Android destination pin

This slice positions a map beneath a fixed centre pin, verifies its stationary
camera target with the authenticated boundary RPC, and returns an in-memory
`DestinationDraft.ExactPoint`. It does not admit, start or persist a Journey.
Coordinates are never saved in `SavedStateHandle`, a saved instance-state Bundle,
Room or disk. Process death discards the unsubmitted draft.

## Dedicated public map credential

The renderer is `org.maplibre.gl:android-sdk-opengl:13.6.1`. Production map styling
uses Geoapify Positron. Set `GEOAPIFY_MAP_TILE_KEY` in the ignored root
`local.properties` or in the build environment. An absent key still compiles and
shows the map-unavailable treatment with Back, retry and canonical locality fallback.

Use a dedicated Geoapify map project/key with its own quota and rotation policy.
This is a public client credential: it is included in `BuildConfig`, the APK and
style/tile requests, and can be extracted. Do not reuse the server geocoding
`GEOAPIFY_API_KEY`, use a service-role key, or commit a real key. There is no tile
proxy or dynamic key-fetch mechanism. Native map transport logging is disabled
because error messages may contain credential-bearing request URLs.

Geoapify style attribution metadata is used through the enabled native MapLibre
attribution control. Required source credits include Geoapify on the Free plan,
OpenStreetMap contributors and OpenMapTiles. The control remains below/right of
the centre pin, inside the viewport rather than beneath the bottom action.
Physical verification of attribution visibility, accessibility and links requires
the dedicated key and a loaded live style. Map attribution is independent of the
reverse-label evidence stored on a draft.

## Verification and labels

Candidate coordinates seed the camera only. General entry fits the authenticated
`get_destination_local_unit_viewport_v1` bounds and waits for a deliberate camera
gesture. After camera idle, verification waits 400 ms and invokes
`verify_destination_coordinate_v1` with the existing Traveller JWT. Only `INSIDE`
with valid operational dataset identity/version enables Continue. Movement cancels
and supersedes verification immediately; generations reject late responses.
Repeated idle callbacks for an unchanged coordinate preserve a pending check rather
than issuing another request; completion preserves the latest camera zoom.

An unchanged verified coordinate retains its proof during temporary network loss.
Moving offline creates an unverified point and disables Continue. Reconnection
checks the latest stationary coordinate, never a point cached before a gesture.

Continue freezes the point and requests `destination-map-label` for that coordinate
only. Server geographic re-verification failure blocks the point. Provider/network
label failure retains the existing proof and uses `Pinned location in {local unit}`.
Only a specific, attributed provider label is retained as reverse-label evidence;
no provider evidence is fabricated for generic fallback. Custom names are optional,
trimmed, limited to 160 Unicode code points and reject control characters. They are
never sent to a provider and cannot change the coordinate or its validity.

The Activity-scoped ViewModel retains logical state through Activity recreation.
The composition owns MapView, native camera listeners, connectivity callbacks and
lifecycle teardown. Naming disables map gestures and preserves the frozen centre.
Back from naming restores the verified positioning state.

Renderer initialization waits for a fully rendered initial frame before the first
coordinate check. A style/tile/renderer initialization failure has a separate map
unavailable state. A malformed reverse-label server contract blocks the exact point;
it cannot be mistaken for an ordinary provider outage.

## Validation and isolated device acceptance

Focused JVM tests use fake gateways and exercise entry, outcomes, cancellation,
stale responses, connectivity, naming and honest draft provenance. HTTP contract
tests assert authenticated paths and exact request bodies. Compose instrumentation
uses fake map content/backends to avoid live-provider costs and cover supporting
states, fixed-pin centring, keyboard/short-screen actions, recreation and blocked
Journey admission. These tests do not prove live map tiles or hosted RPC deployment.

Run `gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest`.
For a connected device, use `assembleM6Acceptance assembleM6AcceptanceAndroidTest`
and the isolated package `com.journeycontinuity.app.m6isolation`. Install only those
acceptance APKs; do not replace, clear or uninstall the normal installed app.

The renderer test uses a local background style by default. The opt-in
`livePositronStyleAndAttributionWithFakeBoundaryGateway` test requires a dedicated
tile key and the instrumentation argument `liveMapTiles=true`; it makes live tile
requests while retaining fake boundary/label gateways. It checks Positron rendering,
source credits/native attribution, general viewport entry, and native restoration.
The optional `captureMap=true` argument stores a fixture screenshot in the isolated
acceptance package's cache. It never captures the normal app.

Full hosted acceptance still needs deployed/authenticated backend foundation.
Exercise candidate/general entry, pan and pinch beneath the
pin, all committed geographic fixtures, both network-loss cases, blank/custom
names, Back, recreation, attribution on short screens, and final draft return
without a Journey or monitoring start. Never claim fake-gateway acceptance as live
boundary/provider acceptance.

Validation recorded on 2026-10-06:

- Focused map ViewModel and gateway JVM tests: 39 passed.
- Full debug JVM suite: 421 passed, with no failures or errors.
- Debug application/instrumentation and isolated acceptance APK builds passed.
- Redmi isolated instrumentation: 14 passed; the missing-key renderer test was
  skipped because the dedicated tile key was configured. The run included the
  existing destination-picker regression tests.
- Live Geoapify Positron rendering and native attribution passed, including general
  viewport entry and native camera restoration. An earlier cold style-load attempt
  timed out; the final run passed. Authoritative boundary/label gateways were fakes.
  The original timeout did not capture its cause, so network variance is a possible
  explanation rather than a confirmed diagnosis. No timeout change was made.
- Connectivity transitions were simulated through the logical state; physical
  radio-loss acceptance and authenticated hosted geographic fixtures remain pending.
- The normal installed app was unchanged. The temporary MIUI background-launch
  permission for the isolated test package was restored to its original setting.

No Supabase deployment, live map-verifier/viewport/label calls, Journey creation,
staging, commit or push was performed for this slice.

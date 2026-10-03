# Alabarin Structured Destination Contract v1

**Status:** Locked product direction for Nigeria-first Traveller v1
**Date:** 2026-10-03
**Applies to:** Start Journey, Journey storage/sync, future Probable End evidence
**Does not implement:** Probable End heuristics, AUTO_ENDED, Trusted Viewer coordinate exposure, or JC1 changes

---

## 1. Why this exists

Alabarin currently stores a Journey destination as free text such as `Ilorin`.

That is enough to display where the Traveller says they are going, but it is not enough for trustworthy destination-aware reasoning.

A future Probable End flow may ask:

> Has this Journey ended?

Alabarin should only ask that when the system has enough evidence to justify the question. A text label alone cannot support that.

The destination model therefore becomes structured while the Traveller-facing experience remains simple.

**Principle: simple for the Traveller, rigorous underneath.**

---

## 2. Product purpose

Structured destination is not being added to make Alabarin a navigation app.

It exists to improve the quality and provenance of Journey understanding.

It should later allow Alabarin to distinguish:

- a named destination from a broad locality
- a precise destination point from an administrative area
- the intended destination from the device's observed position
- system inference from confirmed arrival

A precise destination does **not** mean Alabarin can declare that the Traveller arrived.

---

## 3. Traveller UX direction

The main Start Journey screen remains short.

```text
Start Journey

Where are you going?
[ Choose destination              > ]

When do you expect to arrive?
[ Choose date and time            ]

[ Start Journey ]
```

Tapping **Choose destination** opens a dedicated destination picker.

### Normal Nigeria-first flow

```text
Choose destination

State / FCT
[ Kwara                           > ]

Local government
[ Offa                            > ]

Place or landmark
[ Search e.g. Summit University    ]

Search results...
```

A selected result returns to Start Journey as a concise display label:

```text
Where are you going?
[ Summit University, Offa         > ]
```

### Fallback

If the place cannot be found:

```text
Can't find the place?
[ Choose on map ]
```

The map allows the Traveller to place a destination pin.

A map pin may be given a useful human label such as:

- Grandma's house
- Ogele Junction
- Meeting point
- Hotel entrance

Alabarin must not fabricate an address when the map/search provider cannot establish one.

---

## 4. Nigeria administrative structure

Traveller v1 is Nigeria-first.

For new structured destination selections:

- country is fixed to Nigeria (`NG`)
- State or FCT is required
- LGA is required
- a destination may then be a specific point or a broader locality

State/LGA are primarily **administrative context and disambiguation**.

They are not, by themselves, strong Probable End evidence.

Example:

```text
Kwara
-> Offa
-> Summit University
-> selected point
```

State/LGA help Alabarin find and describe the intended destination. The selected point provides the geographic coordinate used for future distance reasoning.

---

## 5. Destination kinds

v1 supports two semantic destination kinds.

### `POINT`

A specific destination with a coordinate pair.

Examples:

- Summit University
- a hotel
- a motor park
- a house selected by map pin
- a named junction
- a specific meeting point

A `POINT` may later be eligible for Probable End evaluation, subject to a separate evidence contract.

### `LOCALITY`

A broader area rather than an exact point.

Examples:

- Offa
- Gwagwalada
- Ilorin West

A `LOCALITY` is still a valid Journey destination.

However, a locality must not automatically be treated as precise enough for Probable End.

**Journey validity and Probable End eligibility are separate concepts.**

---

## 6. Structured destination domain concept

The exact database field names may vary, but the domain should preserve these facts.

```text
displayLabel
countryCode

stateId
stateName

lgaId
lgaName

kind
  POINT
  LOCALITY

latitude
longitude

selectionSource
  PLACE_SEARCH
  MAP_PIN
  LOCALITY_SELECTION

provider
providerPlaceId

providerResultMetadataVersion / provenance where required

adminDatasetVersion
```

### Rules

For `POINT`:

- latitude and longitude are both present
- coordinate ranges are valid
- source/provenance is known
- display label is nonblank

For `LOCALITY`:

- coordinates are absent
- no fake point is generated from a broad area's centre
- State/FCT and LGA remain explicit

A half-valid coordinate pair is rejected.

---

## 7. Existing `destination` text remains

The existing non-null Journey destination text remains the main human display label.

Examples:

```text
Summit University
Summit University, Offa
Ogele Junction
Offa
```

Home, Active Journey, notifications and result screens continue to consume a concise label.

They should not display:

- raw latitude/longitude
- internal State/LGA IDs
- provider IDs
- provenance metadata

This gives us backward compatibility and keeps the current UI clean.

---

## 8. Historical Journey compatibility

Existing Journeys are not geocoded retroactively.

An old Journey containing:

```text
destination = "Ilorin"
```

remains valid and displayable as:

```text
displayLabel = "Ilorin"
structured destination = unknown
```

Do not infer:

- State
- LGA
- destination coordinate
- destination kind
- provider identity

from historical text.

**No fabricated historical precision.**

---

## 9. Immutability

The destination becomes immutable Journey evidence at the point the Journey is durably created.

After Journey creation:

- display label does not change
- State/LGA snapshot does not change
- coordinate does not change
- selection source/provenance does not change

Changing where the Traveller intends to go requires a future explicitly designed Journey capability, not an ordinary row edit.

The backend should enforce the same immutability as the app.

---

## 10. Geographic reasoning

For a `POINT`, Alabarin compares:

```text
destination coordinate
vs
observed device coordinate
```

using geographic distance, not a simple latitude/longitude rectangular range.

Conceptually:

```text
device observation
(lat, lng, accuracy, observedAt)

        ↓ distance calculation

destination
(lat, lng)
```

The future evidence model must also consider:

- observation freshness
- GPS accuracy
- multiple observations
- movement
- dwell
- evidence continuity

One observation near the destination is not arrival proof.

---

## 11. Administrative areas and polygons

State/LGA boundaries may later be represented using geographic polygons if Alabarin needs to answer questions such as:

> Is this observation inside Offa LGA?

That is a point-in-polygon problem.

It is **not required** for the first structured-destination implementation.

An LGA boundary must not be treated as a Probable End arrival zone.

---

## 12. Place search and map provider decision

### v1 choice

**Place/geocoding service:** Geoapify
**Android map renderer:** MapLibre Native
**Underlying open geographic data:** provider/OpenStreetMap-derived data subject to required attribution

Reasons:

- Geoapify explicitly permits caching/storing geocoding results under its terms.
- Durable storage matters because a selected destination becomes part of the Journey evidence record.
- Geoapify supports geocoding/location APIs without requiring the UI renderer to be tied to one proprietary map SDK.
- MapLibre Native provides an Android-native open-source map renderer.
- The stack leaves Alabarin less coupled to one map vendor.

### Attribution

Required provider/data-source attribution must remain visible wherever provider terms require it, including map presentation.

### API key handling

- do not commit private keys to source control
- use platform-appropriate restricted credentials
- review whether search requests should be proxied through Alabarin backend before production
- prevent provider errors/logging from exposing unnecessary sensitive destination queries

---

## 13. Providers not chosen for v1

### Public OpenStreetMap Nominatim endpoint

Do not use the public Nominatim service as production client-side autocomplete.

Its published usage policy:

- limits heavy usage to 1 request per second
- prohibits client-side autocomplete
- warns commercial applications against depending on the public endpoint

Self-hosted Nominatim remains a future option if needed.

### Mapbox Search Box

Not chosen as the primary v1 search source because Search Box results are documented for temporary use unless storage arrangements are made with Mapbox.

Durable Journey destination storage is a core requirement.

### Google Places

Google has strong search coverage and remains a future option, but Google Places/Geocoding has storage/caching restrictions beyond exempt identifiers such as Place IDs.

For Alabarin v1, the ability to preserve the selected destination record without building around restrictive data-retention rules is preferable.

---

## 14. Nigeria State/FCT + LGA catalogue

Alabarin should ship/use a **versioned static administrative catalogue** rather than require a government service to be online during Journey creation.

Nigeria has:

- 36 States
- the Federal Capital Territory
- 774 LGAs

### Source policy

Canonical names should be vetted against official Nigerian sources such as:

- Independent National Electoral Commission (INEC)
- National Bureau of Statistics (NBS)

The catalogue should record:

```text
alabarinStateId
canonicalStateName
type = STATE | FCT

alabarinLgaId
canonicalLgaName
parentStateId

source
sourceVersion / snapshotDate
```

### Stable IDs

Alabarin should own its internal stable identifiers.

Do not pretend an external agency code is a universal canonical identifier unless that code's contract has been verified.

External official codes may be retained separately as source metadata.

Example internal IDs could conceptually resemble:

```text
NG-KW
NG-KW-OFFA
```

Exact ID format is an implementation decision, but once shipped it should remain stable.

---

## 15. Offline behavior

The State/FCT and LGA catalogue is local and should remain selectable offline.

Place search normally requires provider communication.

If place search is unavailable:

- do not fabricate search results
- clearly show that place search is unavailable
- retain already selected State/LGA draft state
- map availability depends on cached/provider map resources
- starting a structured `POINT` Journey must not occur from a half-selected destination

A broad `LOCALITY` Journey may be allowed only through an explicit locality-selection path, not because point search silently failed.

---

## 16. Journey creation atomicity

A Journey is created only after the complete destination selection passes validation.

Conceptually:

```text
draft destination
+
expected arrival
+
required permissions/readiness
        ↓
validate
        ↓
one durable Journey creation transaction
        ↓
sync / service startup
```

Process death before the durable insert must not create a partial Journey.

Once inserted, the selected destination snapshot is immutable.

---

## 17. Privacy and data minimization

Exact destination coordinates are sensitive.

Rules:

- do not place destination coordinates in notifications
- do not add them to JC1/SMS merely because they now exist
- do not expose them to Trusted Contacts automatically
- do not include them in generic logs/toString output
- review Android backup behavior before precise destination points are stored
- review crash/error reporting before provider queries are introduced
- keep provider query data to the minimum required

The current explicit trusted projections continue to expose the destination label only until a separate product decision changes that.

---

## 18. JC1 / fallback

No JC1 format change is required for structured destination.

JC1 continues to represent degraded-connectivity device evidence.

Destination coordinates remain outside the constrained SMS payload unless a future requirement proves they are necessary.

---

## 19. Probable End boundary

This contract provides the destination foundation only.

It does **not** define the Probable End detector.

Future Probable End work must separately decide:

- required observation accuracy
- maximum observation age
- destination radius / distance semantics
- dwell duration
- movement threshold
- number/continuity of observations
- evidence episode identity
- retrigger rule after **Keep monitoring**
- 30-minute confirmation timing
- automatic terminal semantics

### Locked semantics

```text
near destination
!= arrived

inside destination area
!= safe

device stationary
!= Journey ended

one GPS point
!= sufficient probable-end evidence
```

The future detector's job is only to decide when it is reasonable to **ask**:

> Has this Journey ended?

---

## 20. Relationship to human/device evidence

Structured destination does not change the evidence classes defined in:

`ALABARIN_EVIDENCE_AND_REMOTE_UNCERTAINTY_RULES.md`

In particular:

- device telemetry remains device evidence
- Traveller confirmation remains first-party evidence
- Trusted Contact reports remain attributed human reports
- system inference remains inference
- phone/device silence does not reveal its cause
- a reported Traveller/device separation can change how later device movement is interpreted

---

## 21. Implementation order

1. Add/version Nigeria State/FCT + LGA catalogue.
2. Update Page 05 Figma Start Journey flow with dedicated destination-picker states.
3. Add additive Room/Supabase structured destination fields while preserving `destination` text.
4. Enforce destination immutability.
5. Update sync contracts and old/new-client compatibility.
6. Integrate Geoapify place search.
7. Integrate MapLibre map-pin fallback.
8. Wire picker to atomic Journey creation.
9. Validate hosted sync and real-device destination selection on Redmi.
10. Re-audit privacy, backups, logs and trusted projections.
11. Return to Section 09 and define Probable End evidence from real destination/device data.

---

## 22. Required Figma states before Android picker implementation

At minimum design:

- destination-picker entry from Start Journey
- State/FCT selection
- LGA selection
- place/landmark search empty
- search loading
- search results
- selected result
- no results
- search/provider error
- offline search unavailable
- map-pin selection
- map-pin selected/confirm
- selected destination returned to Start Journey
- longer label/truncation behavior

The main Start Journey page should remain visually simple.

---

## 23. Source references used for provider/data decisions

Official Nigeria administrative structure:

- INEC State Offices & LGA directory: https://www.inecnigeria.org/about/state-offices
- NBS About Us: https://www.nigerianstat.gov.ng/page/about-us
- INEC Polling Units / State-LGA code explanation: https://inecnigeria.org/polling-units/

Provider/storage policies:

- Geoapify: https://www.geoapify.com/
- Geoapify Geocoding API: https://www.geoapify.com/geocoding-api/
- Geoapify pricing: https://www.geoapify.com/pricing/
- MapLibre Native Android: https://maplibre.org/maplibre-native/android/api/
- OSMF Nominatim usage policy: https://operations.osmfoundation.org/policies/nominatim/
- Mapbox Search Box API: https://docs.mapbox.com/api/search/search-box/
- Google Places Place IDs: https://developers.google.com/maps/documentation/places/web-service/place-id

Provider terms and pricing can change. Recheck them before a production launch or material scale increase.

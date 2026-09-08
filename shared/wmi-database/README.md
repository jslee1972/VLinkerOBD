# WMI Database

`wmi-to-brand.json` maps a VIN's WMI (World Manufacturer Identifier, ISO 3780 — normally the
first 3 characters; some manufacturers are only assigned a 2-character family code, listed here
under that 2-character key) to a brand name. It's what auto-selects a vehicle profile after
reading the VIN (Mode 09 or UDS `22F190`) and what labels the detected brand in the UI even when
no PID profile exists for it yet.

## Source and methodology

Base data is [idlesign/vininfo](https://github.com/idlesign/vininfo)'s
`src/vininfo/dicts/wmi.py` (~700 entries, MIT-licensed, actively maintained Python VIN-decoding
library) — a genuinely comprehensive dataset by scope (cars, trucks, buses, motorcycles, spanning
North America, Europe, South America, China, and other Asian markets), not something either of
us should reasonably hand-collect entry by entry. Converted with a small script
(`convert_wmi.py`, not checked in — the transformation is described here) that:

- Resolved the source's object-based entries (e.g. `Nissan()`, `Renault('Infiniti')`) to the
  plain string they'd stringify to, verified by reading vininfo's own `common.py`
  (`Assembler.__init__`: `manufacturer = manufacturer or self.title`, `title` = class name) —
  not guessed.
- Normalized spelling/regional-joint-venture variants onto the exact brand strings this project's
  vehicle profiles already key on (`shared/vehicle-profiles/*.json`'s `brand` field), so a WMI
  resolving to one of these auto-selects the matching PID profile the same way a plain string
  match always did — e.g. `"Mercedes Benz"` → `"Mercedes-Benz"`, `"Citroën"` → `"Citroen"`,
  `"FAW-Volkswagen"` → `"Volkswagen"` (a genuine regional JV building VW-badged cars, not a
  different manufacturer). Only applied where the vehicle is actually sold/badged under that
  brand — a label that could legitimately be either of two brands sharing a WMI (e.g.
  `"DaimlerChrysler AG/Daimler AG"`, used historically for either Mercedes-Benz or Chrysler
  models) was deliberately left unnormalized rather than guessed into one bucket.
- Left every other brand's string close to the source as-is; those only affect the UI's
  detected-brand label, not any PID-profile selection.

## Honesty about coverage

**This is comprehensive, not exhaustive.** There is no single free, complete, authoritative
public registry of every WMI ever assigned (SAE administers the official registry for the
Americas and licenses access to it; other countries' assignments are split across their own
standards bodies) — any dataset claiming "all WMIs" without citing that registry directly is
itself just aggregating other secondary sources, the same as this one. ~700 entries covers the
large majority of what a consumer OBD-II app will actually encounter, including the exact gap
that prompted this (Citroën's Vigo, Spain plant code `VR7`, missing from the previous
hand-curated ~90-entry list) — but a genuinely obscure or newly-issued WMI can still come back
unrecognized. `VehicleBrandDetector.detectBrand()` returning `null` means "not in this database
today," not "not a real car" — if a real-world gap like `VR7` turns up again, the fix is the same
as last time: add the entry, cite the source, log it in
`shared/protocol-docs/vehicle-profile-schema.md`.

## Loading convention

Same pattern as `shared/vehicle-profiles/`: `apps/android/VLinkerOBD/app/src/main/assets/wmi-database/wmi-to-brand.json`
is a manual copy of this file (Android can't reference paths outside its module) — resync after
editing the source here. `VehicleBrandDetector.loadFromJson()` parses it into the lookup map;
`VehicleBrandDetector.FALLBACK` is a small hardcoded subset (just the brands with PID profiles
today) used when no database has been loaded, e.g. in unit tests.

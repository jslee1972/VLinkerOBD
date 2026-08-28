# AGENTS.md

## Repository strategy

This repository is a cross-platform monorepo for the vLinker OBD Dashboard project.

Keep each OS implementation in a separate directory:

```text
apps/ios/
apps/windows/
shared/
docs/
```

Do not mix Swift/iOS source files with C#/Windows source files.

## Language
- Code identifiers: English
- User-facing Chinese: Traditional Chinese only
- Documentation may use Traditional Chinese or clear English

## Architecture

Cross-platform conceptual layers:

```text
BLE / Transport (OS-specific)
        ↓
OBD Command Queue
        ↓
PID Decoder
        ↓
Vehicle Profile
        ↓
Unified VehicleData
        ↓
Dashboard UI (OS-specific)
```

### OS-specific
iOS:
- Swift
- SwiftUI
- CoreBluetooth

Windows:
- C#
- .NET
- WPF preferred
- Windows BLE APIs

### Shared
Keep portable data definitions under `shared/`:
- standard PID definitions
- enhanced PID definitions
- vehicle profiles
- formulas
- units
- common terminology

Do not attempt to directly share Swift or C# binary/source implementation unless explicitly needed.

## OBD rules
1. One ELM/STN command at a time.
2. Wait for `>` prompt before the next command.
3. Implement timeout and retry.
4. Preserve BLE GATT discovery logging.
5. Do not assume all vLinker firmware uses the same UUID.
6. Standard OBD first; EV proprietary data comes later.
7. UI must consume typed VehicleData, not raw OBD text.

## Git workflow

Primary branch: `main`

Recommended per-OS feature branches:
- `ios/<feature>`
- `windows/<feature>`
- `shared/<feature>`

Before coding:
1. `git pull --rebase`
2. inspect root `AGENTS.md`
3. inspect OS-specific README/code

After coding:
1. build/test locally
2. commit only related files
3. `git pull --rebase`
4. resolve conflicts if any
5. push

Never let Mac Codex and Windows Codex edit the same file at the same time unless coordinated.

## Codex reporting
After each task, report:
- files changed
- what changed
- why
- how to test
- known limitations

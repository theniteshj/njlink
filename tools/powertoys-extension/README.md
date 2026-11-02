# NJLINK PowerToys Command Palette / PowerToys Run Extension (`njlink-ptrun`)

`njlink-ptrun` is a standalone, zero-dependency Rust helper that connects Windows launcher tools (PowerToys Command Palette, PowerToys Run, Flow Launcher, Raycast for Windows) to NJLINK's [Local Authenticated API](../../docs/api.md).

## Building

```powershell
cargo build --release --manifest-path tools/powertoys-extension/Cargo.toml
```

## Prerequisites

1. Open **NJLINK → Settings → Local automation API** and turn on **Enable local automation API**.
2. NJLINK writes `%LOCALAPPDATA%\NJLink\api-token.json` containing the loopback port (`53320`) and a 256-bit Bearer token.

## Usage

### Querying Palette Items

```powershell
njlink-ptrun query ""
njlink-ptrun query "ring"
njlink-ptrun query "https://njlink.dev"
```

Outputs a JSON array of palette commands (`send-clipboard`, `ring`, `camera` `photo`/`scan`, `send-url`, `send-text`, plus recent timeline items).

### Invoking an Action

```powershell
njlink-ptrun invoke ring
njlink-ptrun invoke send-clipboard
njlink-ptrun invoke camera photo
njlink-ptrun invoke camera scan
njlink-ptrun invoke send-url "https://njlink.dev"
njlink-ptrun invoke send-text "Copied from PowerToys"
njlink-ptrun invoke send-file "C:\Users\Alice\Desktop\notes.pdf"
```

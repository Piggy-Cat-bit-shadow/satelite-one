# UI and data

AppViewModel owns UI command-client lifetime; pages consume it. The client exists only under canonical foreground/core verdict and establishment includes that verdict (`3c65418`, `3813a23`, `d10c9a5`, `2cb6eb9`). Page-scoped monitoring avoids retaining streams unnecessarily (`060b009`, `7416c73`).

Import supports links, Clash YAML and sing-box forms. Minimal generated config and raw pass-through are separate; raw config must not receive hidden routing/DNS injection (`ac94df1`, `aec3893`, `ebd2f79`). Metadata is app data; traffic/status remains core data. UI tests prove wiring, not remote subscription/proxy availability.

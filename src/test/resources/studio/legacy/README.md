# Legacy migration fixtures

All values are synthetic. No user configuration or credentials are included.

- `mixed-user-values` explicitly sets all 197 delegated properties from `AyuIslandsState`, `SyntaxIntensityBaseState`, and `AccentMappingsState`. Booleans invert their defaults; scalar, map, and set values exercise nondefault and unknown inputs.
- `fresh-absent` has no source XML files. Its expected waveform values come from `freshSettingsState()` and `WaveformConfig`.
- `legacy-omitted` contains existing empty beans. Its expected values come from the legacy `AyuIslandsState` delegates, which differ from fresh initialization.
- `unknown-and-ordered` retains unknown XML elements, nullable/empty values, literal macros, unknown map keys, and encountered collection order.
- `existing-ownership` uses synthetic CGP and Indent Rainbow values from `CodeGlanceCheckpointTest` and `IndentRainbowSyncTest`, root-path lease markers from `RootPathLeaseTest`, and the version-2 inherited Font ownership shape from `FontOwnershipCodec`.

The roots match the public `XmlSerializer` bean format. `isCgpOwnershipMigrated` and `isIrOwnershipMigrated` use their JavaBean XML names without the `is` prefix; the catalog records both identities.

`expected.tsv` columns are source store, Kotlin property, legacy type, and encoded expected interpreted value. Scalars are UTF-8/base64; `-` represents null. Collection items use comma-separated base64 strings; maps separate encoded keys and values with a colon. Empty scalar strings normalize to null through the legacy bean, while their original source representation remains separate.

`source-records.tsv` records store, Kotlin property, original presence, and the exact base64-encoded source option. Absent properties have no row. The migration input carries these source facts separately from interpreted values, and archives the original files byte-for-byte. Native bootstrap capture is verified separately from this pure migration contract.

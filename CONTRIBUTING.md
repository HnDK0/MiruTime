# Contributing to MiruTime

Thanks for taking the time to help. The project is an Android app written in Kotlin with Jetpack Compose.

## Before you start

- **Product decisions** (screens, options, defaults, texts, navigation, new modules) are made by the maintainer. Open an issue and get an agreement before implementing a feature.
- **Plugin and source requests** go to [`HnDK0/external-sources`](https://github.com/HnDK0/external-sources), not here.
- **Security issues** must be reported privately — see [SECURITY.md](SECURITY.md).

## Requirements

- JDK 21
- Android SDK 37 (compile/target), minSdk 26
- Android Studio with the bundled Kotlin/AGP toolchain

## Build and run

```bash
git clone https://github.com/HnDK0/MiruTime
./gradlew :app:assembleDebug
./gradlew :app:installDebug   # device or emulator connected
```

## Tests

Run only the modules you touched; the full suite runs in CI.

```bash
./gradlew :mirutime-core:jvmTest :mirutime-data:jvmTest :translator:jvmTest
./gradlew :core-reader-model:test
./gradlew :ui-kit:testDebugUnitTest :feature-reader:testDebugUnitTest :feature-tts:testDebugUnitTest
./gradlew :<module>:lintDebug
./gradlew :app:testDebugUnitTest --tests '*ArchitectureBoundaryTest'   # import/boundary changes
```

A bug fix needs a regression test. Empty or fully skipped test runs do not count as passing.

## Modules

| Module | Responsibility |
|---|---|
| `:app` | UI, navigation, platform integration, feature wiring |
| `:ui-kit` | Shared design tokens and components |
| `:feature-reader` | Reader UI, paging and reader state |
| `:feature-tts` | Text-to-speech playback and overlay |
| `:core-reader-model` | Pure reader document model |
| `:mirutime-core` | Domain contracts, plugin runtime, storage contracts |
| `:mirutime-data` | Persistence (Room KMP, settings, files) |
| `:translator` | Translation providers and queue |
| `:core-log` | `MiruLog` logging facade |
| `:image-loader` | Image fetching and caching |

Module boundaries are enforced by `ArchitectureBoundaryTest` and dependency tests. Do not add a dependency that crosses them without updating the test.

## Code guidelines

- Kotlin and Compose conventions of the surrounding code; no new dependencies without a clear need.
- Do not silence a diagnostic that points to a real problem. Platform-required annotations (for example `@SuppressLint("SetJavaScriptEnabled")` with its comment) are fine; `@Suppress` to hide a warning you did not fix is not.
- All user-facing strings live in `app/src/main/res/values/strings.xml` and `values-ru/strings.xml`.
- Logs go through `MiruLog` only, with a stable `event=<name>`. Never log chapter bodies, queries, full URLs, cookies, tokens, keys or SAF paths.
- New domain APIs are `suspend`; callbacks are for platform events only (TTS, progress).
- New code should follow the existing patterns for chunked reads (`LIMIT`, keyset), prefs stores and error handling (`Outcome` in `:mirutime-core` and `:translator`).
- Local UI layout stays `private` in its screen; a second use moves it to `:ui-kit`.

## Pull requests

- One logical change per PR; keep unrelated formatting out of the diff.
- Describe what changed and why, link the issue, list the gate commands you ran.
- UI changes: attach a screenshot or a short recording.
- Add a regression test for every bug fix and cover the affected modes (split, parallel, TTS).
- Do not commit secrets, keystores, `local.properties` or generated build output.

## Commits

Use Conventional Commits, English, imperative, lowercase after the prefix, up to 72 characters:

```
feat(reader): translate chapters loaded ahead of the active one
fix(updates): wait for charging before showing next run
```

Scope is the affected area (`reader`, `library`, `finder`, `settings`, `download`, `backup`, `tts`, `video`, `app`, …). Explain what and why in the subject; put details in the body when needed.

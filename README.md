<!--
  android reader, novel reader android, manga reader android, video player android, light novel reader, ranobe reader, epub reader android, fb2 reader android, tts reader, llm translation
  андроид читалка, читалка ранобэ андроид, читалка манги андроид, видеоплеер андроид, читалка новелл, epub читалка андроид, озвучка книг, перевод книг llm
-->
<div align="center">
<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="88" height="88" alt="MiruTime"/>

# MiruTime

Free and open source reader for novels, manga and video on Android.

🇬🇧 English · [🇷🇺 Русский](README_RU.md)

[![Release](https://img.shields.io/github/v/release/HnDK0/MiruTime?style=flat-square&labelColor=27303D&color=0D1117)](https://github.com/HnDK0/MiruTime/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/HnDK0/MiruTime/total?style=flat-square&labelColor=27303D&color=0D1117)](https://github.com/HnDK0/MiruTime/releases)
[![License: GPL-3.0](https://img.shields.io/github/license/HnDK0/MiruTime?style=flat-square&labelColor=27303D&color=0D1117)](LICENSE)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-brightgreen?style=flat-square&labelColor=27303D&color=3DDC84&logo=android&logoColor=white)](https://github.com/HnDK0/MiruTime/releases/latest)

> ⭐️ **If you find MiruTime useful, please consider giving it a star!** It helps the project grow.

</div>

---

## Download

**[Get the latest APK](https://github.com/HnDK0/MiruTime/releases/latest)** — requires Android 8.0+

Each release ships two APKs: `arm64-v8a` for most devices and `universal` for the rest.

Or build from source:

```bash
git clone https://github.com/HnDK0/MiruTime
# Open in Android Studio and run on a device or emulator
```

---

## Features

- Sources as Lua plugins, installed from a repository or a local `.lua` file
- Source catalog with filters, pinned sources and search
- Local EPUB and FB2 library — single books or bulk import
- Reader with font, theme, background and line-spacing settings
- In-reader translation with standard and parallel (original next to translation) modes
- Translation for a single book or selected chapters without touching global settings
- Translation queue for downloaded chapters
- Text-to-speech with background playback and player controls
- Manga reader with a size-capped page cache
- Video player with working-source selection and video downloads
- Download manager: queue, progress and per-type storage cleanup
- Background library updates and in-app update checks
- Regex text cleanup with preview and rule import/export
- Book migration between sources with progress, text and translations
- Backup and restore: manual and scheduled, with backup merging
- Import of NoveLA backup files
- Shelves and library updates, dashboard with reading activity
- EPUB export from the book screen
- Two interface languages: English and Russian
- Light and dark themes, Material 3

---

## Translation

Supported backends:

| Backend | Cost | API key |
|---|---|---|
| Google Translate (Enhanced) | Free | Not required |
| Google Translate (Simple) | Free | Not required |
| Google Gemini | Pay per use | Required |
| OpenAI | Pay per use | Required |
| OpenAI-compatible | Varies by provider | Required |
| Anthropic | Pay per use | Required |
| DeepL | Free and Pro tiers | Required |
| Yandex Cloud Translate | Pay per use | Required (key and folder ID) |
| Yandex Translate (unofficial) | Free | Not required |
| Yandex Browser Translation | Free | Not required |

OpenAI-compatible mode works with OpenRouter, Mistral, DeepSeek, Ollama and any compatible endpoint, including local LLM servers on your network.

Parallel mode displays original and translated text side by side. The language pair is resolved as book → plugin → global settings.

---

## Plugins

Sources are added as external Lua plugins from inside the app: **Finder → Extensions → repository**.

Official plugin repo: [`HnDK0/external-sources`](https://github.com/HnDK0/external-sources)

A standalone `.lua` file can also be installed — it is validated before anything is saved.

---

## 📱 App interface

<div align="center">

### Dashboard and library
| Dashboard | Library | Filters | More screens |
| :---: | :---: | :---: | :---: |
| <img src="screenshots/1.png" width="200"/> | <img src="screenshots/2.png" width="200"/> | <img src="screenshots/3.png" width="200"/> | <img src="screenshots/4.png" width="200"/> |

### Sources, downloads and migration
| Catalog (Finder) | Extensions | Downloads and cache | Migration |
| :---: | :---: | :---: | :---: |
| <img src="screenshots/5.png" width="200"/> | <img src="screenshots/6.png" width="200"/> | <img src="screenshots/19.png" width="200"/> | <img src="screenshots/20.png" width="200"/> |

### Book details and novel reading
| About the book | Chapter list | Text reader | Text-to-speech (TTS) |
| :---: | :---: | :---: | :---: |
| <img src="screenshots/8.png" width="200"/> | <img src="screenshots/9.png" width="200"/> | <img src="screenshots/10.png" width="200"/> | <img src="screenshots/11.png" width="200"/> |

| Translation (LLM) | Style and fonts | Reading options |
| :---: | :---: | :---: |
| <img src="screenshots/12.png" width="200"/> | <img src="screenshots/13.png" width="200"/> | <img src="screenshots/14.png" width="200"/> |

### Manga and video player
| Manga reader | Manga settings | Video player | Player settings |
| :---: | :---: | :---: | :---: |
| <img src="screenshots/15.png" width="200"/> | <img src="screenshots/16.png" width="200"/> | <img src="screenshots/17.png" width="200"/> | <img src="screenshots/18.png" width="200"/> |

### Regex, updates and settings
| Regex cleanup | Updates | Settings |
| :---: | :---: | :---: |
| <img src="screenshots/21.png" width="200"/> | <img src="screenshots/23.png" width="200"/> | <img src="screenshots/7.png" width="200"/> |

</div>

---

## Contributing

Contributions are always welcome — read [CONTRIBUTING.md](CONTRIBUTING.md) first:

- ⭐️ **Star the repository** — the simplest way to support development.
- 🐛 **Report bugs or suggest improvements** by opening an [Issue](https://github.com/HnDK0/MiruTime/issues).
- 🧩 **Fix bugs or add sources** via Pull Requests (use the [external-sources](https://github.com/HnDK0/external-sources) repo for Lua plugins).
- 🔒 **Report vulnerabilities** privately via [SECURITY.md](SECURITY.md), never in a public issue.

---

## Tech stack

Kotlin · Coroutines · Jetpack Compose · Material 3 · Room (KMP) · Ktor · Ksoup · Coil 3 · LuaJ · WorkManager · Media3 · OkHttp · Android TTS & Media APIs

The project is split into modules: `:app`, `:ui-kit`, `:feature-reader`, `:feature-tts`, `:core-reader-model`, `:mirutime-core`, `:mirutime-data`, `:translator`, `:core-log`, `:image-loader`.

---

## License

[GPL-3.0](LICENSE)

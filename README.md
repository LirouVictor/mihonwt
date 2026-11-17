<div align="center">

<img src="./.github/assets/logo.png" alt="MihonWT logo" title="MihonWT logo" width="80"/>

# MihonWT

### Mihon with speech bubble translation

[![Latest release](https://img.shields.io/github/v/release/LirouVictor/mihonwt?labelColor=27303D&color=043b69)](https://github.com/LirouVictor/mihonwt/releases/latest)
[![License: Apache-2.0](https://img.shields.io/github/license/LirouVictor/mihonwt?labelColor=27303D&color=0877d2)](/LICENSE)

</div>

MihonWT is an unofficial personal fork of [Mihon](https://github.com/mihonapp/mihon) that reads the English text in
manga, webtoon and comic pages and draws a Brazilian Portuguese translation over each speech bubble, right in the
reader. Everything else works as in Mihon. It is not affiliated with the Mihon project, so report its issues here, not
upstream.

## Download

Get `MihonWT-vX.Y.Z.apk` from the [latest release](https://github.com/LirouVictor/mihonwt/releases/latest). Once
installed, the app checks this repository for new versions and updates itself, keeping your library and settings.
[Obtainium](https://github.com/ImranR98/Obtainium) pointed at this repository works too.

MihonWT installs beside Mihon (its package is `app.mihonwt`), so you can keep both. It is built for arm64 phones and
requires Android 8.0 or higher.

## Translation

### How a chapter is translated

1. **Reading the pages.** ML Kit's on-device OCR reads the text, and a bundled bubble detector groups it by speech
   bubble and caption, leaving out sound effects and signs. Common OCR mistakes in lettering (`1`/`I`, `0`/`O`, glued
   or line-broken words) are fixed against an English word list and the names already seen in the series.
2. **Translating.** The text of each page goes to the service of your choice, in reading order, together with a
   per-series glossary so names and terms stay the same from chapter to chapter.
3. **Drawing.** Each bubble is painted over and the translation is laid out inside it, as large as the bubble allows,
   with Portuguese hyphenation and without breaking words mid-word. Text outside bubbles is drawn with an outline.

### Services

| Service | Key | Notes |
| --- | --- | --- |
| Google Gemini | Free key from [Google AI Studio](https://aistudio.google.com/apikey) | Default. Sees the page images, so it gets context and fixes OCR slips |
| DeepL | Free key from [DeepL](https://www.deepl.com/pro-api) | Fast and faithful, translates the text OCR read |
| Groq, OpenRouter, Cerebras | Key from the service | Any chat model they offer, picked from a list |
| Other OpenAI-compatible API | Address and key | For services not listed |
| On this device (offline) | None | ML Kit's translator, no internet needed, lower quality |

Each service keeps its own key and model. Keys are encrypted with the Android Keystore and left out of backups, so they
need to be entered again after reinstalling.

### Features

* Translate a chapter from its menu in the chapter list, downloaded or not, and toggle the translation on and off from
  the reader's bottom bar.
* Translate chapters automatically after they download, with a queue and progress notifications.
* The next chapter is translated ahead once you're halfway through a translated one.
* A tap mode that keeps the original lettering and shows the translation of the tapped bubble in a popup.
* Optional text review by Gemini, which looks at the pages and fixes what OCR misread before DeepL or another
  translator that can't see them.
* Optional bubble finding by the AI instead of the on-device detector.
* A starting glossary of each series' characters from [AniList](https://anilist.co/), filled before its first chapter.
* Per-step times and errors with retry in each chapter's translation menu.

Settings are under **Settings › Translation**.

### Current limits

* Source text must be English, and the translation is Brazilian Portuguese only.
* Text written one letter per line is not read.
* Local chapters can't be translated yet.

## Building

MihonWT builds like Mihon: `./gradlew assembleRelease` with JDK 21 and the Android SDK. Releases are built by the
`Release` workflow in GitHub Actions.

## Credits

* [Mihon](https://github.com/mihonapp/mihon), the app this fork is built on, by Javier Tomás and the Mihon Open Source
  Project.
* [TachiyomiAT](https://github.com/mannu691/TachiyomiAT), where the translation module started.
* [comic-text-and-bubble-detector](https://huggingface.co/ogkalu/comic-text-and-bubble-detector) by ogkalu
  (Apache-2.0), the bundled bubble detector, run with [ONNX Runtime](https://onnxruntime.ai/).
* [Comic Neue](https://github.com/crozynski/comicneue) (SIL Open Font License), the font of the translations.
* [SCOWL](http://wordlist.aspell.net/) word lists, used to fix OCR mistakes.
* [ML Kit](https://developers.google.com/ml-kit) text recognition and translation.

Their licences are in the app under **More › About › Open source licenses**.

### Disclaimer

The developer(s) of this application does not have any affiliation with the content providers available, and this
application hosts zero content.

### License

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>

<div align="center">

<img src="./.github/assets/logo.png" alt="MihonWT logo" title="MihonWT logo" width="80"/>

# MihonWT

### Mihon with speech bubble translation

[![Latest release](https://img.shields.io/github/v/release/LirouVictor/mihonwt?labelColor=27303D&color=043b69)](https://github.com/LirouVictor/mihonwt/releases/latest)
[![License: Apache-2.0](https://img.shields.io/github/license/LirouVictor/mihonwt?labelColor=27303D&color=0877d2)](/LICENSE)

</div>

MihonWT is an unofficial personal fork of [Mihon](https://github.com/mihonapp/mihon) that reads the English text in
manga, webtoon and comic pages and draws a translation over each speech bubble, right in the reader. Chapters can be
translated into Brazilian Portuguese, Spanish (Latin America or Spain), French, Italian or German. This fork is
independent of the Mihon project. Please report MihonWT issues in this repository.

## Download

Get `MihonWT-vX.Y.Z.apk` from the [latest release](https://github.com/LirouVictor/mihonwt/releases/latest). Once
installed, use MihonWT's built-in updater to install new versions while keeping your library and settings.

MihonWT installs beside Mihon (its package is `app.mihonwt`), so you can keep both. It is built for arm64 phones and
requires Android 8.0 or higher.

## Translation

### How a chapter is translated

1. **Reading the pages.** ML Kit's on-device OCR reads the text, and a bundled bubble detector groups it by speech
   bubble and caption, leaving out sound effects and signs. Common OCR mistakes in lettering (`1`/`I`, `0`/`O`, glued
   or line-broken words) are fixed against an English word list and the names already seen in the series.
2. **Translating.** The text of each page goes to the service of your choice, in reading order, together with a
   per-series glossary for the target language, so names and terms stay the same from chapter to chapter.
3. **Drawing.** Each bubble is painted over and the translation is laid out inside it, as large as the bubble allows,
   with the target language's hyphenation and without breaking words mid-word. Text outside bubbles is drawn with an outline.

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

* Choose the language to translate into: Brazilian Portuguese, Latin American Spanish, Spanish from Spain, French,
  Italian or German. Every service supports all of them.
* Translate a chapter directly from the chapter list or the reader. The reader's translation button can start a
  translation, show translated pages or return to the original lettering.
* Translate chapters automatically after they download, with a queue and progress notifications.
* The next chapter is translated ahead once you're halfway through a translated one.
* A tap mode that keeps the original lettering and shows the translation of the tapped bubble in a popup.
* Optional text review by Gemini, which looks at the pages and fixes what OCR misread before DeepL or another
  translator that can't see them.
* Optional bubble finding by Gemini from the page images instead of the on-device detector, when Gemini translates.
* A starting glossary of each series' characters from [AniList](https://anilist.co/), filled before its first chapter.
* The chapter's translation menu shows progress, a ready-to-read status and errors with retry. Its timing report
  separates elapsed time from the sum of parallel batches and reports Gemini review separately from translation.

Open **Settings › Translation** to see your current OCR, review service, translator and target language together.
The individual settings are available below the summary.

### Chapter releases and translation groups

The chapter list distinguishes numbered chapters from releases when several groups publish the same chapter. Open
the chapter filter and choose **Choose chapter groups** to see how many distinct chapter numbers each group covers
out of those returned by the source. Choose **Only this group** and confirm to select one of the known groups.
Releases without a group label remain visible. With one group selected, the reader skips repeated numbered chapters
as you advance. The filter uses the chapter list already fetched from the source.

### Current limits

* Source text must be English.
* Vertical lettering and stylized text can be harder to recognize.
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

# Third-party notices

The Windows port includes the Kotlin core from Spicy Lyrics Mobile (revision f005aaa33a51d1fedfab8af15fce9f929dc41407), with desktop adaptations to HTTP transport and optional Japanese dictionary loading. It also adapts the mobile Bloom motion, Spring simulation, cover color extraction, and Kawarp blur and domain warp for Java2D. The Windows display uses system fonts. The adapted code acknowledgements below include provenance from the original mobile notices.

Spicy Lyrics Mobile is licensed under the [GNU Affero General Public License, version 3](LICENSE). It adapts code and data from the projects below. Each keeps its own license and copyright.

## Adapted code

| Project | What Spicy Lyrics Mobile adapts | License |
| --- | --- | --- |
| [Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics) by Spikerko and contributors | The lyrics renderer: layout, word and line animation, scrolling, interludes, the song header, controls and settings styling, TTML parsing and romanization rules, and SLObjPack (the API's lyrics encoding, Kotlin port by Spikerko) | AGPL-3.0 |
| [Spicy Lyrics fork](https://github.com/iPixelGalaxy/spicy-lyrics) by iPixelGalaxy | Settings layout, source cards, the legacy background and pop-up styling | AGPL-3.0 |
| [mild-lyrics](https://github.com/gcoolL/mild-lyrics) Copyright (c) 2026 gc | Source fetching order, lyric blends and Genius romanization alignment | AGPL-3.0-or-later |
| [@kawarp/core](https://github.com/better-lyrics/kawarp) 1.2.2, Copyright (c) 2026 Better Lyrics | The animated background | MIT |
| [cubic-spline](https://github.com/morganherlocker/cubic-spline), Copyright (c) 2014 Morgan Herlocker | Spline interpolation for word animation | MIT |
| [spr](https://github.com/Fraktality/spr), Copyright (c) 2023 Fractality | Spring simulation | MIT |
| [pinyin](https://github.com/hotoo/pinyin) 4.0.0, Copyright (c) 闲耘 | Chinese romanization, its character dictionary (`core/src/main/resources/romanization/pinyin.txt`) and its word dictionary (`core/src/main/resources/romanization/pinyin-phrases.txt`) | MIT |
| [kuroshiro](https://github.com/hexenq/kuroshiro) 1.2.0, Copyright (c) 2015-2021 Hexen Qi | Japanese romanization rules and Hepburn table | MIT |
| [aromanize-js](https://github.com/fujaru/aromanize-js), Copyright (c) 2017 Fajar Chandra | Korean romanization rules | MIT |
| Lyrica's lrcmux adapter | The LRCMux lyrics source | MIT |

## Libraries

The desktop distribution bundles Kotlin, kotlinx.coroutines, Gson, OkHttp, Okio, JetBrains annotations, javax.inject, and Kuromoji under Apache-2.0. It also bundles kXML2 under its BSD-style terms, including the XML Pull API. The Java runtime is distributed with its own license notices in the runtime's `legal` directory.
## MIT License

The MIT-licensed projects above are used under these terms:

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

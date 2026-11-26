# Third-party notices

YSCode bundles or downloads the following software. Each component remains under its own license.

| Component | License | Source |
|---|---|---|
| PRoot (Android build, used as a separate executable `libproot.so` + loader) | GPL-2.0 | https://github.com/green-green-avk/proot and https://github.com/green-green-avk/build-proot-android |
| Alpine Linux and packages downloaded at first run (busybox, musl, git, Node.js, Python, ...) | Various (MIT, GPL, BSD, ...) | https://alpinelinux.org, https://pkgs.alpinelinux.org |
| Kotlin, Jetpack Compose, AndroidX, Room, DataStore, Navigation, Lifecycle | Apache-2.0 | https://developer.android.com, https://kotlinlang.org |
| OkHttp, kotlinx.serialization, kotlinx.coroutines, Coil | Apache-2.0 | https://square.github.io/okhttp/, https://github.com/Kotlin, https://coil-kt.github.io/coil/ |
| Inter (font) | SIL Open Font License 1.1 | https://github.com/rsms/inter |
| JetBrains Mono (font) | SIL Open Font License 1.1 | https://github.com/JetBrains/JetBrainsMono |

Test-only dependencies (JUnit, Robolectric and others) are not shipped in the app.

**GPL source offer.** PRoot is executed as a separate program and is not linked into the YSCode application code. The corresponding
source code of the PRoot binary we ship is available at the links above. On request (open an issue) we will point you to the exact
revision and build scripts used.

Apache-2.0 components are compatible with the GPL-3.0-or-later license of YSCode itself. Full license texts are available from the sources listed.

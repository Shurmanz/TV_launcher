# Дом — домашний экран для TCL Google TV

Минималистичный лаунчер в стиле Apple TV: док любимых приложений, фото природы под время суток,
сезон и погоду в Череповце (Wikimedia Commons + met.no), заставка с часами.

- Сборка без Gradle: `ANDROID_JAR=... TOOLS=... ./build.sh` (javac → d8 → aapt2 → apksig).
- Готовые сборки: `releases/tvhome.apk`, номер версии — `releases/version.json`.
- Ключ подписи (`release.jks`) в репозиторий не кладётся.

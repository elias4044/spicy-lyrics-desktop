# Contributing

Fork the repository and create a branch from `main`. See the [README](README.md) for the Windows toolchain, build commands, and installer.

- Put player and rendering changes in `desktop`, reusable lyrics and provider changes in `core`, and Windows media-session changes in `native`.
- Keep fixes focused. Include steps to reproduce the issue and describe how you verified the change. Use a screenshot or short recording for visual changes.
- Run `.\gradlew.bat :core:test`. For playback or packaging changes, build the Windows package and try **Connect → Try the lyrics demo**.
- Use synthetic lyrics or fixtures you have permission to redistribute. Keep keys, personal settings, and local media out of pull requests.
- Preserve upstream attribution. If you add third-party code or assets, include their license and update [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

By submitting a contribution you agree to license it under the project's [GNU AGPL v3](LICENSE).

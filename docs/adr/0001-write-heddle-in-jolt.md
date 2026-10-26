# 0001. Write heddle in Jolt

Status: Accepted, 2026-10-25

## Context

heddle should ship as one self-contained binary for macOS and Linux. It has to
handle several streams at once reliably and rewrite arbitrary JSON. Speed
matters little. I love Clojure, and multiplexing LSP is mostly reshaping maps of
data, which Clojure is particularly elegant at. However, the Clojure I know runs
on the JVM, and I wanted a standalone binary that people can run without
installing a runtime first. Depending on system libraries such as glibc is fine.

## Options considered

- **Rust**, the safe default, with lspdock as reference code in the same
  language.
- **Go**, probably the best practical fit, but less fun.
- **Clojure with GraalVM native-image**, the proven route to a standalone
  Clojure binary (clojure-lsp ships that way), at the cost of slow builds.
- **Jolt**, a Clojure on Chez Scheme that builds self-contained executables. It
  was young, at v0.8.23, with its repo created in June 2026.
- **Crystal**, which can't build static binaries on macOS, and **TypeScript**
  through Bun or Deno, whose binaries are 50–90 MB.

## Decision

Try Jolt, with Rust as the fallback if it doesn't hold up. A spike showed that
Jolt could relay stdio through `docker compose exec`, round-trip large JSON,
build binaries for other machines, read TOML and handle UTF-16 ([`61aee86`]).
See [KICKOFF.md].

## Consequences

So far, Jolt has held up, and Rust hasn't been needed. Since Jolt is so young,
heddle has needed some workarounds:

- clojure.data.json doesn't run on Jolt, whose strings are code points. So,
  heddle has its own JSON codec ([`c545f62`]).
- Jolt can't fetch Maven dependencies behind an HTTP proxy. TOML support comes
  in as git dependencies instead ([`904b246`]). I rejected vendoring them as a
  kludge.
- `jolt build` doesn't bundle resources, so a macro inlines the built-in runners
  at compile time ([`696598f`]).
- Jolt has no SHA-256. The cache keys repos with CRC-32 instead ([`19f33b9`]).
- Jolt has no release for arm64 Linux. CI builds it from source there, and
  builds on native runners instead of cross-compiling ([`8897ba6`],
  [`99c8c2e`]). The flake can't package the binary yet, because of how Jolt
  fetches git dependencies.

[`61aee86`]: https://github.com/bjeanes/heddle/commit/61aee86bba926c2875f1b61670652d0214edbd67
[`c545f62`]: https://github.com/bjeanes/heddle/commit/c545f62ef73f403cca0f3e62f2ce84e15746fd0b
[`904b246`]: https://github.com/bjeanes/heddle/commit/904b246bbf4c65ec220e427faa88acdfa6f506f0
[`696598f`]: https://github.com/bjeanes/heddle/commit/696598f486025e7acfe9f38464ccad83c80b68e5
[`19f33b9`]: https://github.com/bjeanes/heddle/commit/19f33b9dcb277cc03c92ea46e7f3c1f9654d23af
[`8897ba6`]: https://github.com/bjeanes/heddle/commit/8897ba60f0bdb0e0c3d9e00cb52ffb69f70249ba
[`99c8c2e`]: https://github.com/bjeanes/heddle/commit/99c8c2e3eb4da49fc325e11ab2561a25bf640363
[KICKOFF.md]: https://github.com/bjeanes/heddle/blob/5450235e6ade68e933bf4f2c675f1c3855659ec7/KICKOFF.md#implementation-language

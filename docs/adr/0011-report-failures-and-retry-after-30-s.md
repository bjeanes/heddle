# 0011. Report failures, and retry no sooner than 30 s

Status: Accepted, 2026-10-25, revised 2026-10-26

## Context

A backend can fail to start, hang while starting, or stop. Most clients tuck a
server's stderr away in a log nobody reads. An agent's MCP bridge may not pass
`window/showMessage` on either. At first, a backend that failed to start was
never started again. One that stopped was restarted on the very next `didOpen`,
however soon that came.

## Options considered

- **Only log**, which leaves the user seeing features go missing with no hint
  why.
- **Show a message, and answer requests with an error** explaining the failure,
  so that an agent sees it in its tool's result.
- **Restart immediately, never, or after a delay.**
- **A start deadline of 60 s**, as a review of the codebase suggested, or
  longer.

## Decision

heddle shows an error in the client when a backend fails to start, and a warning
when one stops, using `window/showMessage`. Most editors display that as a
notification. It does so at most once a minute per slot ([`24d6722`]). A failed
backend starts again on the next `didOpen` for one of its files, but no sooner
than 30 s after it failed ([`7bd12f2`]). The reasoning for 30 s and one minute
wasn't recorded. When none of a file's backends is running, requests get
`RequestFailed` with the same message. If any is running, they get the usual
empty answer, so that a failed linter doesn't turn every request into its error
([`355c3b6`]). A backend gets 120 s to start ([`2bc773f`]). That is longer than
suggested, because `prepare` may pull an image the first time. A start that
times out runs `prepare` again from scratch. Each `read` gets 10 s. heddle kills
the whole process tree of anything that runs out of time, and of any backend
still running when it exits ([`acd2517`]). See [Failures].

## Consequences

The limits aren't settings yet. A backend still running `prepare` when heddle
exits has no connection to stop it through, and nothing stops a container that
`prepare` started ([KNOWN-ISSUES.md]).

[`24d6722`]: https://github.com/bjeanes/heddle/commit/24d67221fe604f0daf070c38a4207dd45d967089
[`7bd12f2`]: https://github.com/bjeanes/heddle/commit/7bd12f2d941056b0649a1827e36342b5722f364a
[`355c3b6`]: https://github.com/bjeanes/heddle/commit/355c3b630f030000ecb00e3c8fd4cf12f392b9d7
[`2bc773f`]: https://github.com/bjeanes/heddle/commit/2bc773f1b50f398c23c6df74d8cb5af1e6c0c217
[`acd2517`]: https://github.com/bjeanes/heddle/commit/acd251729c533dc4d18647a082cadf5b86574de9
[Failures]: https://github.com/bjeanes/heddle/blob/acd251729c533dc4d18647a082cadf5b86574de9/DESIGN.md#failures
[KNOWN-ISSUES.md]: https://github.com/bjeanes/heddle/blob/acd251729c533dc4d18647a082cadf5b86574de9/KNOWN-ISSUES.md

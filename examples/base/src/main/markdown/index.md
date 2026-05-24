# Marklit Examples

marklit turns annotated Scala fenced code blocks in markdown into a
compiled, executed, and rendered document. Each block is real Scala —
type-checked, run, and the output spliced back into the markdown.

## Read these in order

1. **[tutorial.md](tutorial.md)** — single-version basics. Visibility
   modifiers (`silent`, `invisible`), assertions (`fail`, `warn`,
   `crash`), `compile-only`, `passthrough`.
2. **[using-deps.md](using-deps.md)** — declaring external libraries
   with `//> using dep` / `//> using deps`.
3. **[using-directives.md](using-directives.md)** — other `//> using`
   directives: compiler options, file-level Scala version, custom
   resolvers. Also covers the `show-warnings=` info-string option.
4. **[scopes-and-versions.md](scopes-and-versions.md)** — sharing state
   across blocks (`id=`, `extends=`, `append`); compiling a block
   against a different Scala version (`scala=` info-string option,
   `shared` / `shared-{major}` setup blocks).
5. **[zio-example.md](zio-example.md)** — worked recipe using
   `marklit:zio-app` plus named scopes to assemble a small ZIO service.

## Modifier quick reference

Modifiers are bare words in the fenced info string, prefixed with
`marklit:` and comma-separated when combined.

| Modifier        | Effect                                                           | Demonstrated in                                       |
| --------------- | ---------------------------------------------------------------- | ----------------------------------------------------- |
| `silent`        | Compile + execute; show source, hide runtime output.             | [tutorial.md](tutorial.md)                            |
| `invisible`     | Compile + execute; hide source AND output (setup-only).          | [tutorial.md](tutorial.md)                            |
| `compile-only`  | Type-check only; do not execute.                                 | [tutorial.md](tutorial.md)                            |
| `passthrough`   | Render verbatim — no compile, no execute, no `scala` tag.        | [tutorial.md](tutorial.md)                            |
| `fail`          | Assert the block fails to compile; render the error.             | [tutorial.md](tutorial.md)                            |
| `warn`          | Assert ≥1 compile warning AND always render warnings.            | [tutorial.md](tutorial.md)                            |
| `crash`         | Assert the block throws at runtime; render the exception.        | [tutorial.md](tutorial.md)                            |
| `zio-app`       | Wrap block as the body of `ZIOAppDefault.run`.                   | [zio-example.md](zio-example.md)                      |
| `shared`        | Prepend block's code to every per-version default scope.         | [scopes-and-versions.md](scopes-and-versions.md)      |
| `shared-{maj}`  | Prepend block to default scopes for one Scala major (`-3`/`-2`). | [scopes-and-versions.md](scopes-and-versions.md)      |
| `append`        | Grow an existing scope in place (combine with `extends=`).       | [scopes-and-versions.md](scopes-and-versions.md)      |

## Info-string option quick reference

Info-string options are `key=value` pairs in the fenced info string,
also prefixed with `marklit:` and comma-separated.

| Option              | Purpose                                                                  | Demonstrated in                                  |
| ------------------- | ------------------------------------------------------------------------ | ------------------------------------------------ |
| `id=<name>`         | Name this block's scope so others can extend it.                         | [scopes-and-versions.md](scopes-and-versions.md) |
| `extends=<name>`    | Inherit definitions from a previously named scope.                       | [scopes-and-versions.md](scopes-and-versions.md) |
| `scala=<version>`   | Per-block Scala version: bare major (`2`/`3`) filters; full version compiles against that compiler. | [scopes-and-versions.md](scopes-and-versions.md) |
| `show-warnings=...` | Per-block override: `true` forces warnings to render; `false` hides them.| [using-directives.md](using-directives.md)       |

## Using-directive quick reference

Using-directives are `//> using <key> <value>` lines inside the code
block itself (not in the fenced info string).

| Directive         | Aliases                                  | Purpose                                |
| ----------------- | ---------------------------------------- | -------------------------------------- |
| `//> using dep`   | `dependency`, `lib`, `library`           | Add one library.                       |
| `//> using deps`  | `dependencies`, `libs`, `libraries`      | Add a comma-separated list of libraries.|
| `//> using scala` | —                                        | File-level Scala version default.      |
| `//> using option`| `scalac`                                 | Add one scalac option.                 |
| `//> using options`| —                                       | Add a comma-separated list of options. |
| `//> using repo` | `repository`                              | Add one Maven repository for resolution.|
| `//> using repos`| `repositories`                            | Add a comma-separated list of repositories.|

## Where to start

If you've never used marklit before, start with
[tutorial.md](tutorial.md). If you already know the basics and want to
share state or compile against multiple Scala versions, jump to
[scopes-and-versions.md](scopes-and-versions.md).

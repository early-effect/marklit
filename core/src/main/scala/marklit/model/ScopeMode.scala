package marklit.model

/** Project-wide scope policy. The default is `Isolated` — every anonymous block
  * compiles in a fresh scope. `Page` shares a single scope across all anonymous
  * blocks of one file (mdoc-style). Future modes can be added without changing
  * call-site signatures.
  */
enum ScopeMode:
  case Isolated
  case Page

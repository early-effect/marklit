# Run-scoped resource

The build configures `marklitRunResourceClass`, so every block shares one
`example.Counter` instance for the whole run, and the resource resets it at run
end. This block increments and prints it:

```scala
println(s"counter = ${example.Counter.value.incrementAndGet()}")
```

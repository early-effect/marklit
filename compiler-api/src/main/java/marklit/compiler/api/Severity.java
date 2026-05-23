package marklit.compiler.api;

/**
 * Diagnostic severity, version-neutral mirror of dotc's reporting levels.
 * Defined in compiler-api so it can cross the per-version classloader boundary.
 */
public enum Severity {
    ERROR,
    WARNING,
    INFO
}

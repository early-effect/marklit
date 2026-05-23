package marklit.compiler.api;

/**
 * Compiler diagnostic in a version-neutral form. POJO so it can be constructed
 * inside the per-version compiler classloader and consumed by marklit's
 * orchestrator without sharing dotc types across the boundary.
 */
public final class Diag {
    private final Severity severity;
    private final String message;
    private final int line;
    private final int column;
    private final String file;

    public Diag(Severity severity, String message, int line, int column, String file) {
        this.severity = severity;
        this.message = message;
        this.line = line;
        this.column = column;
        this.file = file;
    }

    public Severity severity() { return severity; }
    public String message()    { return message; }
    public int line()          { return line; }
    public int column()        { return column; }
    public String file()       { return file; }
}

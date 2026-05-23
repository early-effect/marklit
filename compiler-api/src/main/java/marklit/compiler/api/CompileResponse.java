package marklit.compiler.api;

import java.util.List;

/**
 * Result of a dotc invocation. The shim returns this to the orchestrator
 * across the classloader boundary.
 *
 * - success:      true iff dotc reported no errors.
 * - diagnostics:  errors, warnings, info messages collected during compilation.
 *                 Always populated, even on success (warnings still appear).
 */
public final class CompileResponse {
    private final boolean success;
    private final List<Diag> diagnostics;

    public CompileResponse(boolean success, List<Diag> diagnostics) {
        this.success     = success;
        this.diagnostics = diagnostics;
    }

    public boolean success()        { return success; }
    public List<Diag> diagnostics() { return diagnostics; }
}

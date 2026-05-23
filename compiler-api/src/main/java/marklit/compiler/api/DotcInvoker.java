package marklit.compiler.api;

/**
 * The single entry point that crosses the classloader boundary between
 * marklit's orchestrator and the per-version compiler shim.
 *
 * The orchestrator looks up an implementation class (typically
 * "marklit.compiler.shim.DotcInvokerImpl") via Class.forName(...) on a
 * URLClassLoader that contains the desired scala3-compiler version plus
 * the shim JAR, then casts the instance to this interface. Because this
 * interface lives in compiler-api — visible to BOTH classloaders via a
 * filtering parent loader — the cast succeeds.
 *
 * Implementations must be thread-safe; one DotcInvoker may handle many
 * sequential CompileRequests over a session.
 */
public interface DotcInvoker {

    /**
     * The scala3-compiler version this shim instance was loaded against,
     * as reported by dotty.tools.dotc.config.Properties.versionNumberString.
     * Used by the orchestrator to confirm the loaded compiler matches the
     * version it requested via Coursier.
     */
    String compilerVersion();

    /**
     * Run dotc on the given inputs. Always returns a CompileResponse;
     * compile errors are reported through the response, not exceptions.
     * Exceptions propagate only for genuine infrastructure failures
     * (e.g. dotc itself crashing).
     */
    CompileResponse compile(CompileRequest request);
}

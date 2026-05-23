package marklit.compiler.api;

import java.util.List;

/**
 * Inputs for a single dotc invocation. Constructed by the orchestrator,
 * passed across the classloader boundary into the shim implementation.
 *
 * - sourceFiles: absolute paths to .scala files to compile.
 * - classpath:   absolute paths to JARs and class directories.
 *                Must include scala3-library; the shim does not add one.
 * - outputDir:   absolute path to the directory dotc should write classfiles into.
 * - scalacOptions: extra dotc flags (e.g. "-deprecation"). Must NOT include
 *                  -classpath or -d; those are derived from the fields above.
 */
public final class CompileRequest {
    private final List<String> sourceFiles;
    private final List<String> classpath;
    private final String outputDir;
    private final List<String> scalacOptions;

    public CompileRequest(
        List<String> sourceFiles,
        List<String> classpath,
        String outputDir,
        List<String> scalacOptions
    ) {
        this.sourceFiles   = sourceFiles;
        this.classpath     = classpath;
        this.outputDir     = outputDir;
        this.scalacOptions = scalacOptions;
    }

    public List<String> sourceFiles()   { return sourceFiles; }
    public List<String> classpath()     { return classpath; }
    public String outputDir()           { return outputDir; }
    public List<String> scalacOptions() { return scalacOptions; }
}

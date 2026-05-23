package marklit.compiler.api;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/**
 * Sanity coverage for the Java POJOs that cross the classloader boundary.
 * Every getter must round-trip the constructor argument verbatim — these
 * objects are constructed inside the per-version compiler classloader and
 * read on the other side, so any silent coercion would corrupt diagnostics.
 */
public class PojoTest {

    @Test
    public void diagRoundTripsAllFields() {
        Diag d = new Diag(Severity.ERROR, "boom", 42, 7, "Foo.scala");
        assertEquals(Severity.ERROR, d.severity());
        assertEquals("boom", d.message());
        assertEquals(42, d.line());
        assertEquals(7, d.column());
        assertEquals("Foo.scala", d.file());
    }

    @Test
    public void diagAcceptsNullFile() {
        // dotc emits some diagnostics with no source position (e.g. summary lines).
        Diag d = new Diag(Severity.INFO, "1 error found", 0, 0, null);
        assertNull(d.file());
    }

    @Test
    public void compileRequestRoundTripsAllFields() {
        CompileRequest req = new CompileRequest(
            Arrays.asList("/tmp/A.scala", "/tmp/B.scala"),
            Arrays.asList("/jars/scala3-library.jar"),
            "/tmp/out",
            Arrays.asList("-deprecation", "-feature")
        );
        assertEquals(Arrays.asList("/tmp/A.scala", "/tmp/B.scala"), req.sourceFiles());
        assertEquals(Arrays.asList("/jars/scala3-library.jar"), req.classpath());
        assertEquals("/tmp/out", req.outputDir());
        assertEquals(Arrays.asList("-deprecation", "-feature"), req.scalacOptions());
    }

    @Test
    public void compileResponseSuccessHasNoErrors() {
        CompileResponse ok = new CompileResponse(true, Collections.<Diag>emptyList());
        assertTrue(ok.success());
        assertTrue(ok.diagnostics().isEmpty());
    }

    @Test
    public void compileResponseFailureCarriesDiagnostics() {
        Diag d = new Diag(Severity.ERROR, "type mismatch", 3, 5, "X.scala");
        CompileResponse bad = new CompileResponse(false, Collections.singletonList(d));
        assertFalse(bad.success());
        assertEquals(1, bad.diagnostics().size());
        assertEquals("type mismatch", bad.diagnostics().get(0).message());
    }

    @Test
    public void severityHasExactlyExpectedValues() {
        // If anyone adds a new severity, the orchestrator's mapping needs updating;
        // this test fails loudly if the enum drifts.
        assertEquals(3, Severity.values().length);
        assertEquals(Severity.ERROR, Severity.valueOf("ERROR"));
        assertEquals(Severity.WARNING, Severity.valueOf("WARNING"));
        assertEquals(Severity.INFO, Severity.valueOf("INFO"));
    }
}

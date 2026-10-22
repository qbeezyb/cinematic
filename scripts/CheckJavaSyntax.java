import com.sun.source.util.JavacTask;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/** Parse-only check for a dependency-restricted environment; does not claim type/API validation. */
public final class CheckJavaSyntax {
    public static void main(String[] args) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        List<Path> paths = new ArrayList<>();
        for (String root : List.of("src", "tests", "scripts")) {
            try (var files = Files.walk(Path.of(root))) {
                paths.addAll(files.filter(p -> p.toString().endsWith(".java")).toList());
            }
        }
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null,
                    manager.getJavaFileObjectsFromPaths(paths));
            task.parse();
        }
        boolean failed = false;
        for (var diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) { failed = true; System.err.println(diagnostic); }
        }
        if (failed) { System.exit(1); }
        System.out.println("Parsed " + paths.size() + " Java source files; no syntax errors. Dependency/API checking requires the Gradle build.");
    }
}

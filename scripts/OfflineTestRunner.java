import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.engine.TestExecutionResult;

/** Runs the same JUnit suite using already-cached jars when NeoForge cannot be downloaded. */
public final class OfflineTestRunner {
    public static void main(String[] args) {
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.registerTestExecutionListeners(new TestExecutionListener() {
            @Override public void executionFinished(TestIdentifier test, TestExecutionResult result) {
                if (test.isTest() && result.getStatus() == TestExecutionResult.Status.ABORTED) {
                    System.out.println("ABORTED " + test.getDisplayName() + ": "
                            + result.getThrowable().map(Throwable::getMessage).orElse("No reason given"));
                }
            }
        });
        launcher.execute(LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectPackage("me.cinematic")).build());
        listener.getSummary().printTo(new java.io.PrintWriter(System.out));
        listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out));
        if (listener.getSummary().getTestsFoundCount() == 0 || listener.getSummary().getTotalFailureCount() > 0) {
            System.exit(1);
        }
    }
}

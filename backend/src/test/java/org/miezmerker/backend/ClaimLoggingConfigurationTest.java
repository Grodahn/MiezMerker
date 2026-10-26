package org.miezmerker.backend;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.status.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("node")
@Tag("auth")
class ClaimLoggingConfigurationTest {
    @TempDir Path directory;

    private LoggerContext configure(Path file) throws Exception {
        var context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        if (file != null) context.putProperty("LOG_FILE",file.toString());
        var configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(getClass().getResource("/logback-spring.xml"));
        assertTrue(context.getStatusManager().getCopyOfStatusList().stream().noneMatch(s -> s.getLevel() == Status.ERROR),
            () -> context.getStatusManager().getCopyOfStatusList().toString());
        return context;
    }

    private void bounded(Logger logger, String name) {
        var appender = assertInstanceOf(AsyncAppender.class,logger.getAppender(name));
        assertEquals(256,appender.getQueueSize());
        assertTrue(appender.isNeverBlock());
        assertFalse(appender.isIncludeCallerData());
    }

    @Test void stdoutRemainsDefaultWithoutCreatingAFile() throws Exception {
        var context = configure(null);
        try {
            assertNotNull(context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE"));
            assertNull(context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("FILE"));
            var diagnostic = context.getLogger("org.miezmerker.backend.diagnostics.ClaimDiagnostics");
            bounded(diagnostic,"CLAIM_ASYNC");
            assertNull(diagnostic.getAppender("CLAIM_FILE_ASYNC"));
        } finally { context.stop(); }
    }

    @Test void configuredFileReceivesNormalAndNonblockingDiagnosticLogs() throws Exception {
        Path file = directory.resolve("backend.log");
        var context = configure(file);
        try {
            var root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            assertNotNull(root.getAppender("CONSOLE"));
            assertNotNull(root.getAppender("FILE"));
            root.info("normal-file-marker");
            var diagnostic = context.getLogger("org.miezmerker.backend.diagnostics.ClaimDiagnostics");
            bounded(diagnostic,"CLAIM_ASYNC");
            bounded(diagnostic,"CLAIM_FILE_ASYNC");
            diagnostic.info("diagnostic-file-marker");
            // Verify delivery while the file target is open. Shutdown remains
            // best effort, just like a full nonblocking diagnostic queue.
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (!Files.readString(file).contains("diagnostic-file-marker") && System.nanoTime() < deadline) Thread.sleep(10);
        } finally { context.stop(); }
        String contents = Files.readString(file);
        assertTrue(contents.contains("normal-file-marker"));
        assertTrue(contents.contains("diagnostic-file-marker"));
    }
}

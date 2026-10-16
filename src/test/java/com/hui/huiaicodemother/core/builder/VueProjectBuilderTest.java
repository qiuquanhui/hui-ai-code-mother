package com.hui.huiaicodemother.core.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VueProjectBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void simultaneousBuildsOfSameDirectoryShareResult() throws Exception {
        Path project = project("blog");
        VueProjectBuilder builder = spy(new VueProjectBuilder());
        CountDownLatch installing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger installs = new AtomicInteger();
        doAnswer(invocation -> {
            if ("npm-install.log".equals(invocation.getArgument(3))) {
                installs.incrementAndGet();
                installing.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return true;
        }).when(builder).executeCommand(any(), anyList(), anyInt(), anyString());
        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> builder.buildProject(project.toString()));
        assertTrue(installing.await(5, TimeUnit.SECONDS));
        AtomicBoolean secondResult = new AtomicBoolean();
        Thread second = Thread.ofVirtual().start(() -> secondResult.set(builder.buildProject(project + "/.")));
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                while (second.getState() != Thread.State.WAITING) {
                    Thread.sleep(10);
                }
            });
            assertEquals(1, installs.get());
        } finally {
            release.countDown();
        }
        assertTrue(first.get(5, TimeUnit.SECONDS));
        second.join(5000);
        assertFalse(second.isAlive());
        assertTrue(secondResult.get());
        verify(builder, times(2)).executeCommand(any(), anyList(), anyInt(), anyString());
    }

    @Test
    void differentProjectsCanBuildConcurrently() throws Exception {
        Path firstProject = project("one");
        Path secondProject = project("two");
        VueProjectBuilder builder = spy(new VueProjectBuilder());
        CountDownLatch installs = new CountDownLatch(2);
        doAnswer(invocation -> {
            if ("npm-install.log".equals(invocation.getArgument(3))) {
                installs.countDown();
                assertTrue(installs.await(5, TimeUnit.SECONDS));
            }
            return true;
        }).when(builder).executeCommand(any(), anyList(), anyInt(), anyString());
        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> builder.buildProject(firstProject.toString()));
        CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() -> builder.buildProject(secondProject.toString()));
        assertTrue(first.get(10, TimeUnit.SECONDS));
        assertTrue(second.get(10, TimeUnit.SECONDS));
    }

    @Test
    void failedBuildCanBeRetried() throws Exception {
        Path project = project("retry");
        VueProjectBuilder builder = spy(new VueProjectBuilder());
        doReturn(false, true, true).when(builder).executeCommand(any(), anyList(), anyInt(), anyString());
        assertFalse(builder.buildProject(project.toString()));
        assertTrue(builder.buildProject(project.toString()));
    }

    @Test
    void largeOutputAndStderrAreSavedWithoutBlocking() throws Exception {
        VueProjectBuilder builder = new VueProjectBuilder();
        assertTrue(builder.executeCommand(tempDir.toFile(), fixture("output"), 15, "output.log"));
        String output = Files.readString(tempDir.resolve("output.log"));
        assertTrue(output.length() > 128 * 1024);
        assertTrue(output.contains("stderr-marker"));
    }

    @Test
    void failureRetainsDiagnosticOutput() throws Exception {
        assertFalse(new VueProjectBuilder().executeCommand(tempDir.toFile(), fixture("failure"), 15, "failure.log"));
        assertTrue(Files.readString(tempDir.resolve("failure.log")).contains("missing dependency"));
    }

    @Test
    void timeoutTerminatesProcess() throws Exception {
        assertFalse(new VueProjectBuilder().executeCommand(tempDir.toFile(), fixture("timeout"), 2, "timeout.log"));
        long pid = Long.parseLong(Files.readString(tempDir.resolve("timeout.log")).trim());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    private Path project(String name) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve(name));
        Files.writeString(project.resolve("package.json"), "{}");
        Files.createDirectories(project.resolve("dist"));
        Files.writeString(project.resolve("dist/index.html"), "<html></html>");
        return project;
    }

    private List<String> fixture(String mode) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of("target/test-classes").toAbsolutePath().toString();
        return List.of(java, "-cp", classes, CommandFixture.class.getName(), mode);
    }

    public static class CommandFixture {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "output" -> {
                    System.out.println("x".repeat(256 * 1024));
                    System.err.println("stderr-marker");
                }
                case "failure" -> {
                    System.err.println("missing dependency");
                    System.exit(1);
                }
                case "timeout" -> {
                    System.out.println(ProcessHandle.current().pid());
                    System.out.flush();
                    Thread.sleep(30_000);
                }
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }
}

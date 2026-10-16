package com.hui.huiaicodemother.core.builder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class VueProjectBuilder {

    private final ConcurrentMap<String, CompletableFuture<Boolean>> runningBuilds = new ConcurrentHashMap<>();

    /**
     * 异步构建项目（不阻塞主流程）
     *
     * @param projectPath 项目路径
     */
    public void buildProjectAsync(String projectPath) {
        // 在单独的线程中执行构建，避免阻塞主流程
        Thread.ofVirtual().name("vue-builder-" + System.currentTimeMillis()).start(() -> {
            try {
                buildProject(projectPath);
            } catch (Exception e) {
                log.error("异步构建 Vue 项目时发生异常: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * 构建 Vue 项目
     *
     * @param projectPath 项目根目录路径
     * @return 是否构建成功
     */
    public boolean buildProject(String projectPath) {
        final File projectDir;
        try {
            projectDir = new File(projectPath).getCanonicalFile();
        } catch (IOException e) {
            log.error("无法解析项目目录: {}", projectPath, e);
            return false;
        }
        String projectKey = projectDir.toPath().toUri().normalize().toString();
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        CompletableFuture<Boolean> existing = runningBuilds.putIfAbsent(projectKey, result);
        if (existing != null) {
            log.info("项目正在构建，等待并复用本次构建结果: {}", projectDir);
            try {
                return existing.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException e) {
                log.error("等待 Vue 项目构建失败: {}", projectDir, e);
                return false;
            }
        }
        boolean success = false;
        try {
            success = doBuildProject(projectDir);
            return success;
        } catch (Exception e) {
            log.error("构建 Vue 项目失败: {}", projectDir, e);
            return false;
        } finally {
            result.complete(success);
            runningBuilds.remove(projectKey, result);
        }
    }

    private boolean doBuildProject(File projectDir) {
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            log.error("项目目录不存在: {}", projectDir);
            return false;
        }
        // 检查 package.json 是否存在
        File packageJson = new File(projectDir, "package.json");
        if (!packageJson.exists()) {
            log.error("package.json 文件不存在: {}", packageJson.getAbsolutePath());
            return false;
        }
        log.info("开始构建 Vue 项目: {}", projectDir);
        // 执行 npm install
        if (!executeNpmInstall(projectDir)) {
            log.error("npm install 执行失败");
            return false;
        }
        // 执行 npm run build
        if (!executeNpmBuild(projectDir)) {
            log.error("npm run build 执行失败");
            return false;
        }
        // 验证 dist 目录是否生成
        File distDir = new File(projectDir, "dist");
        if (!new File(distDir, "index.html").isFile()) {
            log.error("构建完成但 dist 目录未生成: {}", distDir.getAbsolutePath());
            return false;
        }
        log.info("Vue 项目构建成功，dist 目录: {}", distDir.getAbsolutePath());
        return true;
    }

    /**
     * 执行 npm install 命令
     */
    private boolean executeNpmInstall(File projectDir) {
        log.info("执行 npm install...");
        return executeCommand(projectDir, List.of(buildCommand("npm"), "install"), 300, "npm-install.log");
    }

    /**
     * 执行 npm run build 命令
     */
    private boolean executeNpmBuild(File projectDir) {
        log.info("执行 npm run build...");
        return executeCommand(projectDir, List.of(buildCommand("npm"), "run", "build"), 180, "npm-build.log");
    }

    /**
     * 判断当前操作系统是否为 Windows
     */
    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    /**
     * 构建命令，根据操作系统添加 .cmd 后缀
     */
    private String buildCommand(String baseCommand) {
        if (isWindows()) {
            return baseCommand + ".cmd";
        }
        return baseCommand;
    }

    /**
     * 执行命令
     *
     * @param workingDir     工作目录
     * @param command        命令及参数
     * @param timeoutSeconds 超时时间（秒）
     * @param logName        输出日志文件名
     * @return 是否执行成功
     */
    boolean executeCommand(File workingDir, List<String> command, int timeoutSeconds, String logName) {
        Process process = null;
        File outputLog = new File(workingDir, logName);
        try {
            log.info("在目录 {} 中执行命令: {}，输出日志: {}", workingDir, command, outputLog);
            // Windows 的 npm.cmd 通过命令解释器启动。
            List<String> processCommand = new ArrayList<>();
            if (isWindows() && command.getFirst().endsWith(".cmd")) {
                processCommand.addAll(List.of("cmd.exe", "/d", "/c"));
            }
            processCommand.addAll(command);
            // 将 stdout 和 stderr 直接写入文件，避免未消费的管道被写满后阻塞 npm。
            process = new ProcessBuilder(processCommand)
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .redirectOutput(outputLog)
                    .start();
            process.getOutputStream().close();
            // 等待进程完成，设置超时
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                log.error("命令执行超时（{}秒），强制终止进程", timeoutSeconds);
                terminateProcess(process);
                logCommandOutput(outputLog);
                return false;
            }
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("命令执行成功: {}", command);
                return true;
            } else {
                log.error("命令执行失败，退出码: {}，完整日志: {}", exitCode, outputLog);
                logCommandOutput(outputLog);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("构建命令被中断: {}", command);
            return false;
        } catch (IOException e) {
            log.error("执行命令失败: {}", command, e);
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                terminateProcess(process);
            }
        }
    }

    private void terminateProcess(Process process) {
        // npm.cmd 还会启动 node 进程，超时或中断时一并终止。
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void logCommandOutput(File outputLog) {
        try (RandomAccessFile file = new RandomAccessFile(outputLog, "r")) {
            int size = (int) Math.min(file.length(), 16 * 1024);
            byte[] tail = new byte[size];
            file.seek(file.length() - size);
            file.readFully(tail);
            log.error("命令输出（最后至多 16KB）:\n{}", new String(tail, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("读取构建日志失败: {}", outputLog, e);
        }
    }

}

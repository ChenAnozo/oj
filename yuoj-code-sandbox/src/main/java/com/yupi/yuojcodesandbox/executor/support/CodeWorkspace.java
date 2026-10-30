package com.yupi.yuojcodesandbox.executor.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

@Component
public class CodeWorkspace {
    private static final Logger log = LoggerFactory.getLogger(CodeWorkspace.class);

    @Value("${codesandbox.workspace-root:}")
    private String workspaceRoot = "";

    @Value("${codesandbox.host-workspace-root:}")
    private String hostWorkspaceRoot = "";

    private Path rootPath() {
        return (workspaceRoot.isEmpty()
                ? Paths.get(System.getProperty("user.dir"), "tmpCode")
                : Paths.get(workspaceRoot)).toAbsolutePath().normalize();
    }

    public Path dockerBindPath(Path directory) {
        Path absolute = directory.toAbsolutePath().normalize();
        if (hostWorkspaceRoot.isEmpty()) {
            return absolute;
        }
        Path root = rootPath();
        if (!absolute.startsWith(root)) {
            throw new IllegalArgumentException("Docker workspace is outside configured root");
        }
        return Paths.get(hostWorkspaceRoot).resolve(root.relativize(absolute)).normalize();
    }

    public Path create() throws IOException {
        Path root = rootPath();
        Files.createDirectories(root);
        return Files.createDirectory(root.resolve(UUID.randomUUID().toString()));
    }

    public void save(Path directory, String filename, String code) throws IOException {
        Files.write(directory.resolve(filename), code.getBytes(StandardCharsets.UTF_8));
    }

    public Path createInputFile(Path workspacePath, String input) throws IOException {
        Path inputPath = Files.createTempFile(workspacePath, ".stdin-", ".txt");
        try {
            Files.write(inputPath, input.getBytes(StandardCharsets.UTF_8));
            return inputPath;
        } catch (IOException | RuntimeException e) {
            cleanupInputFile(inputPath);
            throw e;
        }
    }

    public void cleanupInputFile(Path inputPath) {
        try { Files.deleteIfExists(inputPath); }
        catch (Exception e) { log.warn("Input file cleanup failed: {}", inputPath, e); }
    }

    public void cleanup(Path directory) {
        if (directory == null) { return; }
        try {
            // Do not follow links created by submitted programs outside the workspace.
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                    if (error != null) { throw error; }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.info("[沙箱任务 {}] 工作目录已清理", directory.getFileName());
        } catch (Exception e) {
            log.warn("Workspace cleanup failed: {}", directory, e);
        }
    }
}

package com.yupi.yuojcodesandbox.executor;

import com.yupi.yuojcodesandbox.executor.support.CodeWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CodeWorkspaceMappingTest {
    @TempDir Path root;

    @Test void translatesSharedVolumePathsAndRejectsOutsideDirectories() throws Exception {
        CodeWorkspace workspaces = new CodeWorkspace();
        Path containerRoot = root.resolve("container");
        Path hostRoot = root.resolve("daemon-volume");
        ReflectionTestUtils.setField(workspaces, "workspaceRoot", containerRoot.toString());
        ReflectionTestUtils.setField(workspaces, "hostWorkspaceRoot", hostRoot.toString());
        Path directory = workspaces.create();
        assertEquals(hostRoot.resolve(directory.getFileName()), workspaces.dockerBindPath(directory));
        assertThrows(IllegalArgumentException.class, () -> workspaces.dockerBindPath(root.resolve("outside")));
        workspaces.cleanup(directory);
    }

    @Test void retainsNativeAbsolutePathsWhenMappingIsNotConfigured() {
        assertEquals(root.toAbsolutePath(), new CodeWorkspace().dockerBindPath(root));
    }
}

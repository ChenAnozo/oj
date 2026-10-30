package com.yupi.yuojcodesandbox.executor;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.yupi.yuojcodesandbox.executor.runner.DockerImageValidator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DockerImageValidatorTest {
    @Test void validatesBothConfiguredImagesAndDeduplicatesSharedImage() {
        DockerClient client = mock(DockerClient.class);
        InspectImageCmd inspect = mock(InspectImageCmd.class);
        when(client.inspectImageCmd(anyString())).thenReturn(inspect);
        new DockerImageValidator(client, "java-pinned", "cpp-pinned").afterPropertiesSet();
        verify(client).inspectImageCmd("java-pinned");
        verify(client).inspectImageCmd("cpp-pinned");
        verify(inspect, times(2)).exec();
        reset(client, inspect);
        when(client.inspectImageCmd("shared")).thenReturn(inspect);
        new DockerImageValidator(client, "shared", "shared").afterPropertiesSet();
        verify(inspect, times(1)).exec();
    }

    @Test void missingImageStopsStartupWithActionableFailure() {
        DockerClient client = mock(DockerClient.class);
        InspectImageCmd inspect = mock(InspectImageCmd.class);
        when(client.inspectImageCmd("missing")).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new NotFoundException("missing"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new DockerImageValidator(client, "missing", "cpp").afterPropertiesSet());
        assertTrue(error.getMessage().contains("missing at startup: missing"));
        verify(client, never()).createContainerCmd(anyString());
    }

    @Test void dockerConnectionFailureIsNotSilentlyIgnored() {
        DockerClient client = mock(DockerClient.class);
        InspectImageCmd inspect = mock(InspectImageCmd.class);
        when(client.inspectImageCmd("java")).thenReturn(inspect);
        IllegalStateException connectionFailure = new IllegalStateException("Docker unavailable");
        when(inspect.exec()).thenThrow(connectionFailure);
        assertSame(connectionFailure, assertThrows(IllegalStateException.class,
                () -> new DockerImageValidator(client, "java", "cpp").afterPropertiesSet()));
    }
}

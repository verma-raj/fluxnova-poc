package com.example.isaworker.gui;

import com.example.isaworker.IsaWorkerApplicationTestAccess;
import com.example.isaworker.config.GuiProperties;
import org.finos.fluxnova.bpm.client.task.ExternalTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class DialogServicesTest {

    private static ExternalTask task(Object name) {
        ExternalTask task = mock(ExternalTask.class);
        when(task.getProcessInstanceId()).thenReturn("42");
        when(task.getTopicName()).thenReturn("enter-name-topic");
        when(task.getVariable("name")).thenReturn(name);
        return task;
    }

    @Test
    void headlessTakesTheAnswerFromTheProcessVariable() {
        Optional<String> answer = new HeadlessDialogService().askText("Please enter your name:", task("  Rajiv Verma "), "name");

        assertThat(answer).contains("Rajiv Verma");
    }

    @Test
    void headlessWithoutTheVariableIsACancelledDialog(CapturedOutput output) {
        assertThat(new HeadlessDialogService().askText("Please enter your name:", task(null), "name")).isEmpty();
        assertThat(new HeadlessDialogService().askText("Please enter your name:", task("   "), "name")).isEmpty();
        assertThat(output).contains("process instance 42 has no variable 'name'");
    }

    @Test
    void headlessShowsReadOnlyTextInTheLog(CapturedOutput output) {
        new HeadlessDialogService().showReadOnly("Worker details", "Name: Rajiv Verma\nAddress: Bangalore", task("x"));

        assertThat(output).contains("Worker details").contains("Name: Rajiv Verma | Address: Bangalore");
    }

    @Test
    void swingRefusesWithAClearMessageWhenTheJvmHasNoDisplay() {
        SwingDialogService withoutDisplay = new SwingDialogService() {
            @Override
            protected boolean headless() {
                return true;
            }
        };

        assertThatThrownBy(() -> withoutDisplay.askText("Please enter your name:", task(null), "name"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No display is available").hasMessageContaining("isaworker.gui.mode=headless");
        assertThatThrownBy(() -> withoutDisplay.showReadOnly("Worker details", "x", task(null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No display is available");
    }

    @Test
    void theConfiguredModeDecidesTheImplementation() {
        for (Map.Entry<GuiProperties.Mode, String> entry : Map.of(GuiProperties.Mode.HEADLESS, "headless",
                GuiProperties.Mode.SWING, "swing").entrySet()) {
            GuiProperties gui = new GuiProperties();
            gui.setMode(entry.getKey());
            new ApplicationContextRunner().withUserConfiguration(DialogConfiguration.class).withBean(GuiProperties.class, () -> gui)
                    .run(context -> assertThat(context.getBean(DialogService.class).mode()).isEqualTo(entry.getValue()));
        }
    }

    @Test
    void remoteModeNeedsTheSharedSecret() {
        GuiProperties gui = new GuiProperties();
        gui.setMode(GuiProperties.Mode.REMOTE);
        new ApplicationContextRunner().withUserConfiguration(DialogConfiguration.class).withBean(GuiProperties.class, () -> gui)
                .withPropertyValues("isaworker.gui.mode=remote").withBean(com.example.isaworker.gui.remote.DialogBroker.class)
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("isaworker.gui.remote.token"));
    }

    @Test
    void remoteModeWithTheSecretUsesTheBrokerAndReportsTheAgent() {
        GuiProperties gui = new GuiProperties();
        gui.setMode(GuiProperties.Mode.REMOTE);
        gui.getRemote().setToken("secret");
        new ApplicationContextRunner().withUserConfiguration(DialogConfiguration.class).withBean(GuiProperties.class, () -> gui)
                .withPropertyValues("isaworker.gui.mode=remote").withBean(com.example.isaworker.gui.remote.DialogBroker.class)
                .run(context -> {
                    DialogService service = context.getBean(DialogService.class);
                    assertThat(service.mode()).isEqualTo("remote");
                    assertThat(service.details()).containsEntry("mode", "remote").containsEntry("agentConnected", false)
                            .containsEntry("dialogsWaiting", 0);
                });
    }

    @Test
    void autoMatchesWhatTheJvmHas() {
        GuiProperties gui = new GuiProperties();
        gui.setMode(GuiProperties.Mode.AUTO);
        String expected = java.awt.GraphicsEnvironment.isHeadless() ? "headless" : "swing";
        new ApplicationContextRunner().withUserConfiguration(DialogConfiguration.class).withBean(GuiProperties.class, () -> gui)
                .run(context -> assertThat(context.getBean(DialogService.class).mode()).isEqualTo(expected));
    }

    @Test
    void aDesktopIsAssumedOnWindowsAndMacButOnLinuxOnlyWithADisplayServer() {
        assertThat(IsaWorkerApplicationTestAccess.hasDesktop("Windows 11", name -> null)).isTrue();
        assertThat(IsaWorkerApplicationTestAccess.hasDesktop("Mac OS X", name -> null)).isTrue();
        assertThat(IsaWorkerApplicationTestAccess.hasDesktop("Linux", name -> null)).isFalse();
        assertThat(IsaWorkerApplicationTestAccess.hasDesktop("Linux", name -> "DISPLAY".equals(name) ? ":0" : null)).isTrue();
        assertThat(IsaWorkerApplicationTestAccess.hasDesktop("Linux", name -> "WAYLAND_DISPLAY".equals(name) ? "wayland-0" : null)).isTrue();
        assertThat(IsaWorkerApplicationTestAccess.hasDesktop("Linux", name -> " ")).isFalse();
    }
}

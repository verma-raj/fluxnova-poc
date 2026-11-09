package com.example.isaworker.gui;

import com.example.isaworker.config.GuiProperties;
import com.example.isaworker.gui.remote.DialogBroker;
import com.example.isaworker.gui.remote.RemoteDialogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.awt.GraphicsEnvironment;

/** Chooses, once, how the workers show dialogs ({@code isaworker.gui.mode}). */
@Slf4j
@Configuration
public class DialogConfiguration {

    @Bean
    public DialogService dialogService(GuiProperties gui, ObjectProvider<DialogBroker> broker) {
        boolean headless = GraphicsEnvironment.isHeadless();
        DialogService service = switch (gui.getMode()) {
            case REMOTE -> remote(gui, broker);
            case SWING -> new SwingDialogService();
            case HEADLESS -> new HeadlessDialogService();
            case AUTO -> headless ? new HeadlessDialogService() : new SwingDialogService();
        };
        log.info("Dialogs: {} (isaworker.gui.mode={}, java.awt.headless={})", service.mode(), gui.getMode().name().toLowerCase(),
                headless);
        if (service instanceof SwingDialogService && headless) {
            log.warn("isaworker.gui.mode=swing, but this JVM has no display: the interactive tasks will fail. Use "
                    + "isaworker.gui.mode=remote with the dialog agent on the desktop instead.");
        }
        return service;
    }

    private static DialogService remote(GuiProperties gui, ObjectProvider<DialogBroker> broker) {
        String token = gui.getRemote().getToken();
        if (token == null || token.isBlank()) {
            // Without the secret anybody who can reach the port could answer the dialogs: refuse to start rather than run open.
            throw new IllegalStateException("isaworker.gui.mode=remote needs isaworker.gui.remote.token "
                    + "(environment variable ISAWORKER_DIALOG_TOKEN), the shared secret of the worker and the dialog agent.");
        }
        log.info("Dialogs open on the desktop of the host: a dialog agent has to connect to this worker's /api/dialogs.");
        return new RemoteDialogService(broker.getObject(), gui.getRemote());
    }
}

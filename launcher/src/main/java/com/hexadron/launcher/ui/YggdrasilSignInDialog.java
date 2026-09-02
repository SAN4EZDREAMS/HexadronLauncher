/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 SAN4EZDREAMS. All rights reserved.
 *
 * Licensed for noncommercial use only. You may use, study, share and improve
 * this software; you may not sell it, and you may not remove, alter or obscure
 * this notice or the authorship it records. Full terms: LICENSE.md in the
 * project root. Provided without any warranty.
 *
 * SPDX-License-Identifier: LicenseRef-Hexadron-NC-1.0
 */

package com.hexadron.launcher.ui;

import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.skin.SkinCredentials;
import com.hexadron.launcher.skin.YggdrasilAuth;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;

/**
 * Signing in to a third-party skin service.
 *
 * <h2>Why the password is asked for here</h2>
 *
 * <p>Yggdrasil has no browser flow. The service hands out a token in exchange
 * for the account's password and nothing else, so a launcher that supports
 * these services has to ask for it. What this window can do - and does - is
 * keep it: the password is held in a field, sent once, and never written
 * anywhere. What comes back is a token pair, which goes to the operating
 * system's credential store and can be revoked from the service's own site.
 *
 * <p>It is also why the address is shown rather than typed here. A password
 * typed into a window that is about to send it somewhere should show where,
 * and the launcher refuses to send one over anything but HTTPS.
 *
 * <h2>The button does the work, not OK</h2>
 *
 * <p>A sign-in is a network call that can take seconds and can fail, and a
 * dialog whose OK button blocks the interface while it runs, then closes
 * whatever the answer was, is the shape that produces "it did nothing". So the
 * press is intercepted: the call runs on a worker, the button says so, a
 * failure is written under the fields in the service's own words, and the
 * window closes only once there is a session to close it with.
 */
final class YggdrasilSignInDialog {

    private final String service;
    private final String clientToken;

    private final TextField user = new TextField();
    private final PasswordField password = new PasswordField();
    private final Label message = new Label();

    private YggdrasilAuth.Session session;

    /**
     * @param clientToken this account's existing client token, or null to mint
     *                    a new one - kept across sign-ins so that renewing a
     *                    token here does not sign the same account out
     *                    somewhere else
     */
    YggdrasilSignInDialog(String service, String clientToken) {
        this.service = YggdrasilAuth.normalise(service);
        this.clientToken = clientToken == null || clientToken.isBlank()
                ? SkinCredentials.newClientToken() : clientToken;
    }

    /** The address is checked by the caller, which has somewhere to say so. */
    Optional<YggdrasilAuth.Session> show(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(I18n.t("signin.title"));
        dialog.setHeaderText(null);
        dialog.setResizable(false);

        ButtonType ok = new ButtonType(I18n.t("signin.button"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.t("dialog.cancel"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, cancel);

        Label address = new Label(service);
        address.getStyleClass().add("muted");
        address.setWrapText(true);
        address.setMinHeight(Region.USE_PREF_SIZE);

        user.setPromptText(I18n.t("signin.user.hint"));
        message.getStyleClass().add("muted");
        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);
        message.setMaxWidth(360);

        VBox content = new VBox(6,
                label("signin.service"), address,
                label("signin.user"), user,
                label("signin.password"), password,
                message);
        content.setPadding(new Insets(18, 18, 8, 18));
        content.setPrefWidth(380);
        dialog.getDialogPane().setContent(content);
        Theme.apply(dialog.getDialogPane());

        Button signIn = (Button) dialog.getDialogPane().lookupButton(ok);
        signIn.addEventFilter(ActionEvent.ACTION, event -> {
            // Consumed every time: the window closes from the worker below, and
            // only when there is something to close it with.
            event.consume();
            attempt(dialog, ok, signIn);
        });

        Platform.runLater(user::requestFocus);
        dialog.showAndWait();
        return Optional.ofNullable(session);
    }

    private void attempt(Dialog<ButtonType> dialog, ButtonType ok, Button button) {
        String name = user.getText() == null ? "" : user.getText().trim();
        if (name.isEmpty() || password.getText() == null || password.getText().isEmpty()) {
            message.setText(I18n.t("signin.incomplete"));
            return;
        }

        button.setDisable(true);
        message.setText(I18n.t("signin.busy"));
        String secret = password.getText();

        Thread worker = new Thread(() -> {
            YggdrasilAuth.Session got = null;
            String failure = null;
            try {
                got = YggdrasilAuth.authenticate(service, name, secret, clientToken);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = I18n.t("signin.failed", e.toString());
            } catch (Exception e) {
                failure = I18n.t("signin.failed", YggdrasilAuth.describe(e));
            }

            YggdrasilAuth.Session result = got;
            String shown = failure;
            Platform.runLater(() -> {
                button.setDisable(false);
                if (result == null) {
                    message.setText(shown);
                    return;
                }
                session = result;
                dialog.setResult(ok);
                dialog.close();
            });
        }, "skin-service-sign-in");
        worker.setDaemon(true);
        worker.start();
    }

    private static Label label(String key) {
        Label label = new Label(I18n.t(key));
        label.getStyleClass().add("form-label");
        return label;
    }
}

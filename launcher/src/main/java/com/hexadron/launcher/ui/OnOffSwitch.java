/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 OLEKSII RADCHUK (SAN4EZDREAMS). All rights reserved.
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
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.Objects;

/**
 * An on/off switch, drawn the way a phone draws one: a rounded track with a
 * knob that sits on the right when the item is on and on the left when it is
 * off, and a short word on the free side of the track.
 *
 * <p>It replaces the "Disable" / "Enable" button in the rows of the mods, data
 * packs, resource packs and shaders lists. A button that names the action
 * shows the opposite of the state, so a player had to read the word and invert
 * it to know whether a mod was on. The switch shows the state itself: the knob
 * position, the word and a colour all say the same thing.
 *
 * <p>The colours are muted on purpose - a dark green for on, a dark red for off.
 * A list of forty mods is forty switches, and full-strength colours would make
 * the switches the loudest thing in the window. They only have to be told apart
 * at a glance.
 *
 * <p>It is a {@link Button}, so everything a row already does with its button
 * still works: {@code setOnAction}, {@code setDisable}, a tooltip, focus, and
 * Space or Enter from the keyboard. A click does not change the state here. The
 * row asks for the change, and the switch moves when the list shows the new
 * state - so a change that is refused or cancelled never shows as done.
 *
 * <p>Styled in {@code hexadron.css} under {@code .on-off-switch}; the state is
 * the {@code :on} pseudo-class.
 */
public final class OnOffSwitch extends Button {

    private static final PseudoClass ON = PseudoClass.getPseudoClass("on");

    private static final Duration SLIDE = Duration.millis(150);

    private static final double KNOB = 18;

    private final Region knob = new Region();
    private final Label onWord = new Label();
    private final Label offWord = new Label();

    /**
     * Both words in one stack, so the track is as wide as the longer word in
     * either state. Without this the switch would change width when it flips,
     * and the buttons next to it would jump.
     */
    private final StackPane words = new StackPane(onWord, offWord);

    private final HBox track = new HBox(6);

    private boolean on;

    /** True once {@link #show} has been called; the first call never animates. */
    private boolean shown;

    /** What the switch last showed the state of, so a flip of it can animate. */
    private Object shownFor;

    private ParallelTransition motion;

    public OnOffSwitch() {
        getStyleClass().add("on-off-switch");
        setAccessibleRole(AccessibleRole.TOGGLE_BUTTON);

        knob.getStyleClass().add("on-off-knob");
        knob.setMinSize(KNOB, KNOB);
        knob.setPrefSize(KNOB, KNOB);
        knob.setMaxSize(KNOB, KNOB);

        onWord.getStyleClass().add("on-off-word");
        offWord.getStyleClass().add("on-off-word");
        onWord.setMinWidth(Region.USE_PREF_SIZE);
        offWord.setMinWidth(Region.USE_PREF_SIZE);
        words.setAlignment(Pos.CENTER);

        track.getStyleClass().add("on-off-track");
        track.setAlignment(Pos.CENTER_LEFT);
        track.setMinWidth(Region.USE_PREF_SIZE);

        setText(null);
        setGraphic(track);
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        setMinWidth(Region.USE_PREF_SIZE);

        arrange();
    }

    /**
     * Shows the state of one item.
     *
     * <p>List cells are reused for other items as the list scrolls, so the
     * switch only slides when it shows a new state of the <em>same</em> item -
     * that is, when the item was just switched. For another item it jumps to
     * the new position at once; a slide there would be motion that means
     * nothing.
     *
     * @param value    true when the item is on
     * @param identity what the item is, stable across a switch; may be null
     */
    public void show(boolean value, Object identity) {
        onWord.setText(I18n.t("mods.switch.on"));
        offWord.setText(I18n.t("mods.switch.off"));
        // What a screen reader says is the action, as the old button did.
        setAccessibleText(I18n.t(value ? "mods.disable" : "mods.enable"));

        boolean animate = shown && value != on && identity != null
                && Objects.equals(identity, shownFor) && getScene() != null;
        shown = true;
        shownFor = identity;
        if (value == on) {
            return;
        }

        double before = knob.getLayoutX();
        on = value;
        arrange();
        if (!animate) {
            stopMotion();
            return;
        }
        // Lay the track out now, so the knob's new place is known, then start
        // it from where it was and slide it the rest of the way.
        track.applyCss();
        track.layout();
        double after = knob.getLayoutX();
        stopMotion();
        knob.setTranslateX(before - after);

        TranslateTransition slide = new TranslateTransition(SLIDE, knob);
        slide.setToX(0);
        slide.setInterpolator(Interpolator.EASE_BOTH);
        FadeTransition fade = new FadeTransition(SLIDE, words);
        fade.setFromValue(0);
        fade.setToValue(1);
        motion = new ParallelTransition(slide, fade);
        motion.setOnFinished(event -> motion = null);
        motion.play();
    }

    /** True when the switch shows the on state. */
    public boolean isOn() {
        return on;
    }

    /** Puts the knob and the word on their sides, and sets the colour state. */
    private void arrange() {
        pseudoClassStateChanged(ON, on);
        onWord.setVisible(on);
        offWord.setVisible(!on);
        // On: the word on the left, the knob on the right. Off: the reverse.
        if (on) {
            track.getChildren().setAll(words, knob);
        } else {
            track.getChildren().setAll(knob, words);
        }
    }

    private void stopMotion() {
        if (motion != null) {
            motion.stop();
            motion = null;
        }
        knob.setTranslateX(0);
        words.setOpacity(1);
    }
}

package com.kubedesk;

import atlantafx.base.theme.PrimerLight;
import com.kubedesk.ui.MainView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.Objects;

/**
 * JavaFX application entry point. Applies the AtlantaFX theme, layers KubeDesk's own stylesheet on
 * top (Kubernetes-Dashboard-like palette), and shows the main window.
 */
public class App extends Application {

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());

        // Size the window to fit the actual screen work area (excludes the taskbar). On smaller
        // displays — e.g. a 1366x768 VDI — a hardcoded 760px-tall window would spill below the screen
        // and hide the bottom status bar until you maximized; clamping to the visual bounds avoids that.
        Rectangle2D vb = Screen.getPrimary().getVisualBounds();
        double w = Math.min(1180, vb.getWidth());
        double h = Math.min(760, vb.getHeight());

        MainView mainView = new MainView();
        Scene scene = new Scene(mainView, w, h);
        scene.getStylesheets().add(
                Objects.requireNonNull(getClass().getResource("/styles/app.css")).toExternalForm());

        stage.setTitle("KubeDesk");
        var iconStream = getClass().getResourceAsStream("/icons/kubedesk.png");
        if (iconStream != null) {
            stage.getIcons().add(new Image(iconStream));
        }
        stage.setScene(scene);
        stage.setMinWidth(Math.min(900, vb.getWidth()));
        stage.setMinHeight(Math.min(600, vb.getHeight()));
        // Keep the window fully on-screen (centered in the work area).
        stage.setX(vb.getMinX() + (vb.getWidth() - w) / 2);
        stage.setY(vb.getMinY() + (vb.getHeight() - h) / 2);
        // Stop watches and worker threads so the JVM exits promptly when the window closes.
        stage.setOnCloseRequest(e -> {
            mainView.shutdown();
            Platform.exit();
            System.exit(0);
        });
        stage.show();

        mainView.init();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

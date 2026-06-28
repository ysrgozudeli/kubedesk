package com.kubedesk;

import atlantafx.base.theme.PrimerLight;
import com.kubedesk.ui.MainView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
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

        MainView mainView = new MainView();
        Scene scene = new Scene(mainView, 1180, 760);
        scene.getStylesheets().add(
                Objects.requireNonNull(getClass().getResource("/styles/app.css")).toExternalForm());

        stage.setTitle("KubeDesk");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
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

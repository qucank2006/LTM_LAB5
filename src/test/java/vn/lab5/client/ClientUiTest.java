package vn.lab5.client;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import vn.lab5.common.*;
import vn.lab5.server.*;

/** Native JavaFX test against exported RMI and SQLite. Requires a desktop. */
class ClientUiTest {
  @TempDir Path dir;

  static <T> T fx(Callable<T> call) throws Exception {
    FutureTask<T> task = new FutureTask<>(call);
    Platform.runLater(task);
    return task.get(10, TimeUnit.SECONDS);
  }

  static void await(Callable<Boolean> check) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < end) {
      if (check.call()) return;
      Thread.sleep(50);
    }
    fail("JavaFX operation timed out");
  }

  @BeforeAll
  static void startup() throws Exception {
    CountDownLatch ready = new CountDownLatch(1);
    Platform.startup(
        () -> {
          Platform.setImplicitExit(false);
          ready.countDown();
        });
    assertTrue(ready.await(10, TimeUnit.SECONDS));
  }

  @AfterAll
  static void shutdown() {
    Platform.exit();
  }

  @Test
  void connectCreateEditRefreshAndOfflineResponsiveness() throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    System.setProperty("java.rmi.server.hostname", "127.0.0.1");
    try (Store store = new Store(dir.resolve("ui.db").toString(), "UI")) {
      Service service = new Service(store, "UI");
      Registry registry = LocateRegistry.createRegistry(port);
      registry.rebind(NoteService.BINDING, UnicastRemoteObject.exportObject(service, 0));
      ClientApp app = new ClientApp();
      Stage stage =
          fx(
              () -> {
                Stage s = new Stage();
                app.start(s);
                return s;
              });
      try {
        fx(
            () -> {
              ((TextField) stage.getScene().lookup("#host")).setText("127.0.0.1");
              ((TextField) stage.getScene().lookup("#port")).setText("" + port);
              ((Button) stage.getScene().lookup("#connect")).fire();
              return null;
            });
        await(
            () ->
                fx(
                    () ->
                        ((Label) stage.getScene().lookup("#status"))
                            .getText()
                            .startsWith("Đã kết nối")));
        fx(
            () -> {
              ((TextField) stage.getScene().lookup("#title")).setText("UI note");
              ((TextArea) stage.getScene().lookup("#content")).setText("JavaFX → RMI → SQLite");
              ((Button) stage.getScene().lookup("#save")).fire();
              return null;
            });
        await(() -> store.all("notes").size() == 1);
        await(() -> fx(() -> !stage.getScene().lookup("#save").isDisabled()));
        fx(
            () -> {
              table(stage).getSelectionModel().selectFirst();
              ((TextField) stage.getScene().lookup("#title")).setText("UI edited");
              ((Button) stage.getScene().lookup("#save")).fire();
              return null;
            });
        await(() -> store.all("notes").getFirst().version() == 2);
        await(() -> fx(() -> !stage.getScene().lookup("#refresh").isDisabled()));
        service.save(null, "other client", "refresh", 0);
        fx(
            () -> {
              ((Button) stage.getScene().lookup("#refresh")).fire();
              return null;
            });
        await(() -> fx(() -> table(stage).getItems().size() == 2));
        assertEquals(
            "UI edited",
            store.all("notes").stream()
                .filter(n -> n.version() == 2)
                .findFirst()
                .orElseThrow()
                .title());
        UnicastRemoteObject.unexportObject(service, true);
        fx(
            () -> {
              ((Button) stage.getScene().lookup("#refresh")).fire();
              return null;
            });
        assertEquals(42, fx(() -> 42));
        await(
            () ->
                fx(
                    () ->
                        ((Label) stage.getScene().lookup("#status"))
                            .getText()
                            .startsWith("Thao tác thất bại")));
      } finally {
        fx(
            () -> {
              for (javafx.stage.Window w : java.util.List.copyOf(javafx.stage.Window.getWindows()))
                w.hide();
              app.stop();
              return null;
            });
        try {
          UnicastRemoteObject.unexportObject(service, true);
        } catch (java.rmi.NoSuchObjectException ignored) {
          /* already unexported for offline test */
        }
        UnicastRemoteObject.unexportObject(registry, true);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private TableView<Note> table(Stage stage) {
    return (TableView<Note>) stage.getScene().lookup("#notes");
  }
}

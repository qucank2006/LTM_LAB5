package vn.lab5.client;

import java.rmi.registry.LocateRegistry;
import java.util.List;
import java.util.concurrent.*;
import javafx.application.Application;
import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import vn.lab5.common.*;

public final class ClientApp extends Application {
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "rmi-client");
            t.setDaemon(true);
            return t;
          });
  private final TableView<Note> table = new TableView<>();
  private final TextField host = new TextField("localhost"),
      port = new TextField("1099"),
      title = new TextField();
  private final TextArea content = new TextArea();
  private final Label status = new Label("Chưa kết nối"), editing = new Label("Ghi chú mới");
  private final Button connect = new Button("Kết nối"),
      refresh = new Button("Làm mới"),
      save = new Button("Lưu"),
      delete = new Button("Xóa"),
      fresh = new Button("Tạo mới");
  private NoteService service;
  private Note selected;
  private boolean busy;

  @Override
  public void start(Stage stage) {
    System.setProperty("sun.rmi.transport.tcp.responseTimeout", "5000");
    System.setProperty("sun.rmi.transport.connectionTimeout", "5000");
    System.setProperty("sun.rmi.transport.tcp.handshakeTimeout", "5000");
    host.setPromptText("IP / hostname server");
    port.setPrefWidth(85);
    host.setPrefWidth(240);
    host.setId("host");
    port.setId("port");
    title.setId("title");
    content.setId("content");
    table.setId("notes");
    connect.setId("connect");
    save.setId("save");
    delete.setId("delete");
    refresh.setId("refresh");
    fresh.setId("new");
    status.setId("status");
    HBox connection =
        new HBox(10, new Label("Server"), host, new Label("RMI port"), port, connect, refresh);
    connection.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
    column("Tiêu đề", Note::title, 240);
    column("Nội dung", Note::content, 340);
    column("Phiên bản", n -> Long.toString(n.version()), 90);
    column(
        "Cập nhật",
        n ->
            java.time.Instant.ofEpochMilli(n.updatedAt())
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
                .toString(),
        190);
    table.setPlaceholder(new Label("Kết nối server để xem ghi chú"));
    table
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, a, n) -> {
              if (n != null) {
                selected = n;
                title.setText(n.title());
                content.setText(n.content());
                editing.setText("Sửa: " + n.id());
              }
              controls();
            });
    title.setPromptText("Tiêu đề (tối đa 200 ký tự)");
    content.setPromptText("Nội dung (tối đa 100.000 ký tự)");
    content.setWrapText(true);
    VBox form = new VBox(10, editing, title, content, new HBox(10, fresh, save, delete));
    VBox.setVgrow(content, Priority.ALWAYS);
    form.setPrefWidth(340);
    SplitPane split = new SplitPane(table, form);
    split.setDividerPositions(.68);
    VBox root = new VBox(14, new Label("LAB 5 · RMI NOTES"), connection, status, split);
    root.setPadding(new Insets(20));
    VBox.setVgrow(split, Priority.ALWAYS);
    connect.setOnAction(
        e -> {
          String h = host.getText().trim();
          final int p;
          try {
            p = Integer.parseInt(port.getText().trim());
            if (h.isBlank() || p < 1 || p > 65535) throw new IllegalArgumentException();
          } catch (RuntimeException ex) {
            error("Nhập hostname và port hợp lệ (1–65535).");
            return;
          }
          service = null;
          table.getItems().clear();
          clear();
          run(
              () -> {
                NoteService s =
                    (NoteService) LocateRegistry.getRegistry(h, p).lookup(NoteService.BINDING);
                String id = s.ping();
                List<Note> notes = s.list();
                return new ConnectionResult(s, id, notes);
              },
              r -> {
                service = r.service();
                table.getItems().setAll(r.notes());
                status.setText("Đã kết nối " + r.id() + " @ " + h + ":" + p);
              });
        });
    refresh.setOnAction(e -> load());
    fresh.setOnAction(e -> clear());
    save.setOnAction(
        e -> {
          String t = title.getText(), c = content.getText();
          Note n = selected;
          if (t.isBlank() || t.length() > 200 || c.length() > 100_000) {
            error("Tiêu đề bắt buộc, tối đa 200 ký tự; nội dung tối đa 100.000 ký tự.");
            return;
          }
          run(
              () -> {
                service.save(n == null ? null : n.id(), t, c, n == null ? 0 : n.version());
                return service.list();
              },
              notes -> {
                table.getItems().setAll(notes);
                clear();
              });
        });
    delete.setOnAction(
        e -> {
          Note n = selected;
          if (n == null) return;
          Alert confirm =
              new Alert(
                  Alert.AlertType.CONFIRMATION,
                  "Xóa ghi chú “" + n.title() + "”?",
                  ButtonType.OK,
                  ButtonType.CANCEL);
          if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
          run(
              () -> {
                service.delete(n.id(), n.version());
                return service.list();
              },
              notes -> {
                table.getItems().setAll(notes);
                clear();
              });
        });
    Scene scene = new Scene(root, 1180, 660);
    scene.getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
    stage.setScene(scene);
    stage.setTitle("Lab 5 – Đồng bộ ghi chú Java RMI");
    stage.setMinWidth(850);
    stage.setMinHeight(500);
    controls();
    stage.show();
  }

  private record ConnectionResult(NoteService service, String id, List<Note> notes) {}

  private void column(String name, java.util.function.Function<Note, String> f, int width) {
    TableColumn<Note, String> c = new TableColumn<>(name);
    c.setCellValueFactory(v -> new SimpleStringProperty(f.apply(v.getValue())));
    c.setPrefWidth(width);
    table.getColumns().add(c);
  }

  private void clear() {
    selected = null;
    table.getSelectionModel().clearSelection();
    title.clear();
    content.clear();
    editing.setText("Ghi chú mới");
    controls();
  }

  private void controls() {
    connect.setDisable(busy);
    host.setDisable(busy);
    port.setDisable(busy);
    refresh.setDisable(busy || service == null);
    save.setDisable(busy || service == null);
    delete.setDisable(busy || service == null || selected == null);
    fresh.setDisable(busy);
    table.setDisable(busy);
    title.setDisable(busy);
    content.setDisable(busy);
  }

  private void load() {
    run(
        () -> service.list(),
        notes -> {
          table.getItems().setAll(notes);
          clear();
        });
  }

  private <T> void run(Callable<T> work, java.util.function.Consumer<T> success) {
    busy = true;
    controls();
    Task<T> task =
        new Task<>() {
          protected T call() throws Exception {
            return work.call();
          }
        };
    task.setOnSucceeded(
        e -> {
          busy = false;
          success.accept(task.getValue());
          controls();
        });
    task.setOnFailed(
        e -> {
          busy = false;
          status.setText("Thao tác thất bại — kiểm tra mạng, kết nối lại hoặc làm mới");
          error(task.getException().getMessage());
          controls();
        });
    executor.submit(task);
  }

  private void error(String message) {
    Alert a = new Alert(Alert.AlertType.ERROR);
    a.setHeaderText("Không thể thực hiện thao tác");
    a.setContentText(message == null ? "Lỗi không xác định" : message);
    a.show();
  }

  @Override
  public void stop() {
    executor.shutdownNow();
  }

  public static void main(String[] args) {
    launch(args);
  }
}

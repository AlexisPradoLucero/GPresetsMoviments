import gearth.extensions.ExtensionForm;
import gearth.extensions.ExtensionInfo;
import gearth.extensions.parsers.HEntity;
import gearth.extensions.parsers.HEntityType;
import gearth.extensions.parsers.HEntityUpdate;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.*;


@ExtensionInfo(
        Title = "GPresetsMoviments",
        Description = "Capture user moviments",
        Version = "1.0",
        Author = "AlexisPrado & Julianty"
)

public class GPresetsMoviments extends ExtensionForm {
    public Button RecMoviments;
    public Button StopRecMoviments;
    public Button ImportPreset;
    public Button PlayPreset;
    public ListView<String> ListPresets;
    public CheckBox delayCheckBox;
    public Button reloadpresets;
    TreeMap<Integer, Integer> UserIdAndIndex = new TreeMap<>();
    TreeMap<Integer, String> UserIdAndName = new TreeMap<>();
    public String UserMoviments;
    public int UserId = -1;
    public int X, Y;
    LinkedList<JSONObject> walks = new LinkedList<>();
    private int currentCoordinateIndex = 0;
    private long lastUserUpdateMillis = 0;
    private boolean isFirstUserUpdate = true;
    private boolean recordingCoordinates = false;
    private List<List<Data>> presetsCoordinates = new ArrayList<>(); // Lista de listas de coordenadas
    private String presetsFolder;

    // When the user open the extension

    @Override
    protected void onShow() {
    }

    // When the user close the extension
    @Override
    protected void onHide() {
        UserIdAndIndex.clear();
        UserIdAndName.clear();
        UserId = -1;
        X = 0;
        Y = 0;
    }

    @Override
    protected void initExtension() {
        // primaryStage.setOnShowing(e->{});
        // primaryStage.setOnCloseRequest(e -> {});

        // Runs when the textfield changes

        presetsFolder = presetPath();

        File folder = new File(presetsFolder);
        if (!folder.exists()) {
            folder.mkdirs();
        }

        ListPresets.getItems().clear();

        // Obtener la lista de archivos en la carpeta de presets
        File presetsFolder = new File(presetPath());
        File[] presetFiles = presetsFolder.listFiles();

        if (presetFiles != null) {
            // Recorrer los archivos de presets y cargarlos en el ListView
            for (File presetFile : presetFiles) {
                if (presetFile.isFile() && presetFile.getName().endsWith(".json")) {
                    String presetName = presetFile.getName().replace(".json", "");
                    ListPresets.getItems().add(presetName);
                    presetsCoordinates.add(importDataFromFile(presetFile));
                }
            }
        }

        intercept(HMessage.Direction.TOSERVER, "Chat", this::onChatSend);

        intercept(HMessage.Direction.TOSERVER, "GetSelectedBadges", hMessage -> {
            UserId = hMessage.getPacket().readInteger();
            try {
                UserMoviments = UserIdAndName.get(UserId);
                System.out.println(UserMoviments);

                // Enviar mensaje al chat
                sendToClient(new HPacket("Chat", HMessage.Direction.TOCLIENT, -1, "Selected user: " + UserMoviments, 0, 30, 0, 0));
            } catch (NullPointerException ignored) {
            }
        });
        intercept(HMessage.Direction.TOSERVER, "OpenFlatConnection", hMessage -> {
            walks.clear();
            UserIdAndIndex.clear();
            UserIdAndName.clear();
            UserId = -1;
            X = 0;
            Y = 0;
            // Resto de la lógica de interceptación del paquete OpenFlatConnection
        });

        intercept(HMessage.Direction.TOCLIENT, "Users", hMessage -> {
            //IdAndIndex.clear(); Al no borrar la lista esos datos se almacenan ojo con eso
            try {
                HPacket hPacket = hMessage.getPacket();
                HEntity[] roomUsersList = HEntity.parse(hPacket);
                for (HEntity hEntity : roomUsersList) {
                    if (hEntity.getEntityType().equals(HEntityType.HABBO)) {
                        // El ID del usuario no esta en el Map (Dictionary en c#)
                        if (!UserIdAndIndex.containsKey(hEntity.getId())) {
                            UserIdAndIndex.put(hEntity.getId(), hEntity.getIndex());
                            UserIdAndName.put(hEntity.getId(), hEntity.getName());
                        } else { // Se especifica la key, para remplazar el value por uno nuevo
                            UserIdAndIndex.replace(hEntity.getId(), hEntity.getIndex());
                            UserIdAndName.replace(hEntity.getId(), hEntity.getName());
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        // Get the user coords
        intercept(HMessage.Direction.TOCLIENT, "UserUpdate", hMessage -> {
            try {
                if (recordingCoordinates) {
                    for (HEntityUpdate hEntityUpdate : HEntityUpdate.parse(hMessage.getPacket())) {
                        if (hEntityUpdate.getIndex() == UserIdAndIndex.get(UserId)) {
                            X = hEntityUpdate.getMovingTo().getX();
                            Y = hEntityUpdate.getMovingTo().getY();

                            System.out.println(X + " " + Y);

                            // Crea un objeto JSON para almacenar las coordenadas
                            JSONObject coordinateObj = new JSONObject();
                            coordinateObj.put("x", X);
                            coordinateObj.put("y", Y);

                            long currentMillis = System.currentTimeMillis();
                            long elapsedMillis;

                            if (isFirstUserUpdate) {
                                // Ignorar el primer paquete y establecer el lastUserUpdateMillis
                                isFirstUserUpdate = false;
                                lastUserUpdateMillis = currentMillis;
                                elapsedMillis = 100; // Valor que quieras asignar para el primer paquete
                            } else {
                                elapsedMillis = currentMillis - lastUserUpdateMillis;
                                lastUserUpdateMillis = currentMillis;
                            }
                            coordinateObj.put("delay", elapsedMillis);
                            walks.add(coordinateObj);
                            System.out.println("Delay: " + elapsedMillis);
                        }
                    }
                }
            } catch (Exception exception) {
            }
        });
    }

    public void handleRecMoviments() {
        recordingCoordinates = true;
        isFirstUserUpdate = true;
        lastUserUpdateMillis = 0;
        walks.clear(); // Limpia la lista de coordenadas para empezar de cero
        sendToClient(new HPacket("Chat", HMessage.Direction.TOCLIENT, -1, "Recording the user...", 0, 30, 0, 0));
    }

    public void handleStopRecMoviments() {
        recordingCoordinates = false;
        sendToClient(new HPacket("Chat", HMessage.Direction.TOCLIENT, -1, "Finished recording", 0, 30, 0, 0));
    }

    public void handlePlayPreset() {
        int selectedIndex = ListPresets.getSelectionModel().getSelectedIndex();
        if (selectedIndex >= 0) {
            List<Data> selectedPreset = presetsCoordinates.get(selectedIndex);
            currentCoordinateIndex = 0;

            // Obtener el valor del CheckBox
            boolean useJsonDelay = delayCheckBox.isSelected();

            // Inicia un nuevo hilo para ejecutar el movimiento
            Thread moveThread = new Thread(() -> moveNextCoordinate(selectedPreset, useJsonDelay));
            moveThread.start();
        }
    }

    private void moveNextCoordinate(List<Data> selectedPreset, boolean useJsonDelay) {
        while (currentCoordinateIndex < selectedPreset.size()) {
            Data data = selectedPreset.get(currentCoordinateIndex);
            sendToServer(new HPacket("MoveAvatar", HMessage.Direction.TOSERVER, data.getX(), data.getY()));
            currentCoordinateIndex++;

            // Agrega un retraso antes de pasar a la siguiente coordenada
            try {
                if (useJsonDelay) {
                    long delay = data.getDelay().intValue();
                    Thread.sleep(delay); // Lee el valor del delay del JSON si el CheckBox está marcado
                } else {
                    Thread.sleep(500); // Retraso fijo de 500 ms si el CheckBox no está marcado
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // Se ejecutaron todas las coordenadas, detener el hilo
        Thread.currentThread().interrupt();
        sendToClient(new HPacket("Chat", HMessage.Direction.TOCLIENT, -1, "Preset played successfully", 0, 30, 0, 0));
    }

    public static String presetPath() {
        try {
            String path = (new File(GPresetsMoviments.class.getProtectionDomain().getCodeSource().getLocation().toURI()))
                    .getParentFile().toString();
            return Paths.get(path, "presetsmoviments").toString();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        return "";
    }

    private void onChatSend(HMessage hMessage) {
        String msg = hMessage.getPacket().readString();
        if (msg.startsWith(":save")) {
            hMessage.setBlocked(true);

            if (msg.length() > 6) {
                String presetName = msg.substring(6).trim(); // Obtener el nombre del preset desde el mensaje de chat

                if (!presetName.isEmpty()) {
                    String presetFileName = presetName.replaceAll("[^a-zA-Z0-9]", "_") + ".json";
                    String filePath = presetsFolder + File.separator + presetFileName;

                    try {
                        JSONArray jsonArray = new JSONArray(walks);

                        // Crea un BufferedWriter para escribir en el archivo de preset
                        BufferedWriter writer = new BufferedWriter(new FileWriter(filePath));
                        writer.write(jsonArray.toString(4));
                        writer.close();

                        presetsCoordinates.add(importDataFromFile(new File(filePath)));

                        // Actualizar el ListView en el hilo de la aplicación JavaFX
                        Platform.runLater(() -> ListPresets.getItems().add(presetName));

                        sendToClient(new HPacket("Chat", HMessage.Direction.TOCLIENT, -1, "Preset saved successfully: " + presetName, 0, 30, 0, 0));
                    } catch (IOException e) {
                        // Mostrar mensaje de error en el hilo de la aplicación JavaFX
                        Platform.runLater(() -> {
                            sendToClient(new HPacket("Chat", HMessage.Direction.TOCLIENT, -1, "Error saving preset: " + e.getMessage(), 0, 30, 0, 0));
                        });
                    }
                }
            }
        }
    }

    public void handleImportPreset() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Importar datos desde archivo JSON");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Archivos JSON", "*.json"));

        // Muestra el diálogo para que el usuario seleccione el archivo a importar
        File file = fileChooser.showOpenDialog(primaryStage);

        if (file != null) {
            List<Data> importedCoordinates = importDataFromFile(file);

            // Agrega las coordenadas importadas a la lista de listas de coordenadas
            presetsCoordinates.add(importedCoordinates);

            // Agrega el nombre del archivo importado al ListView
            ListPresets.getItems().add(file.getName());

            System.out.println("Datos importados correctamente.");
        }
    }
    private List<Data> importDataFromFile(File file) {
        List<Data> importedData = new ArrayList<>();

        try {
            // Crea un BufferedReader para leer el contenido del archivo
            BufferedReader reader = new BufferedReader(new FileReader(file));
            StringBuilder content = new StringBuilder();
            String line;

            // Lee el contenido del archivo línea por línea
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }

            // Cierra el BufferedReader después de leer el contenido
            reader.close();

            // Convierte el contenido del archivo a JSONArray
            JSONArray jsonArray = new JSONArray(content.toString());

            // Recorre el JSONArray y obtiene las coordenadas "x" e "y"
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject coordinateObj = jsonArray.getJSONObject(i);
                int x = coordinateObj.getInt("x");
                int y = coordinateObj.getInt("y");
                Long delay = coordinateObj.getLong("delay");
                importedData.add(new Data(x, y, delay));
            }

        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }

        return importedData;
    }

    public void handlereloadpresets() {
        ListPresets.getItems().clear(); // Limpia la lista actual de presets

        // Obtener la lista de archivos en la carpeta de presets
        File presetsFolder = new File(presetPath());
        File[] presetFiles = presetsFolder.listFiles();

        if (presetFiles != null) {
            // Recorrer los archivos de presets y cargarlos en el ListView
            for (File presetFile : presetFiles) {
                if (presetFile.isFile() && presetFile.getName().endsWith(".json")) {
                    String presetName = presetFile.getName().replace(".json", "");
                    ListPresets.getItems().add(presetName);
                }
            }
        }

        sendToClient(new HPacket("Chat", HMessage.Direction.TOCLIENT, -1, "Presets reloaded", 0, 30, 0, 0));
    }

    // Clase para almacenar las coordenadas "x" e "y" como un objeto
    private static class Data {
        private final int x;
        private final int y;

        private final Long delay;

        public Data(int x, int y, Long delay) {
            this.x = x;
            this.y = y;
            this.delay = delay;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public Long getDelay() {
            return delay;
        }

        @Override
        public String toString() {
            return "x: " + x + ", y: " + y + ", delay: " + delay;
        }
    }
}
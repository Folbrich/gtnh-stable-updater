package GTNHNightlyUpdater.Gui;

import GTNHNightlyUpdater.Config.UpdateRequest;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * UI-bound state for a single "instance" row (a client or server folder to update) in the
 * STABLE screen's instance list.
 */
public class InstanceRow {

    private final StringProperty path = new SimpleStringProperty("");
    private final ObjectProperty<UpdateRequest.Side> side = new SimpleObjectProperty<>(UpdateRequest.Side.CLIENT);

    public StringProperty pathProperty() {
        return path;
    }

    public ObjectProperty<UpdateRequest.Side> sideProperty() {
        return side;
    }

    public String getPath() {
        return path.get();
    }

    public UpdateRequest.Side getSide() {
        return side.get();
    }
}

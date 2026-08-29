package GTNHNightlyUpdater;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class JsonParser {
    private static final Gson GSON = new GsonBuilder().create();

    public static <T> T parse(String json, Class<T> clazz) {
        return GSON.fromJson(json, clazz);
    }
}

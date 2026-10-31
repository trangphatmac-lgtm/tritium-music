import com.google.gson.*;
import java.nio.file.*;
import tritium.ncm.music.effects.EmotionModelRuntime;

/** Offline comparison runner; Gson is already a project dependency. */
public final class CompareEmotionModel {
    public static void main(String[] args) throws Exception {
        JsonObject oracle = new JsonParser().parse(Files.readString(Path.of(args[1]))).getAsJsonObject();
        Gson gson = new Gson();
        double allMax = 0, effectiveMax = 0, squared = 0;
        String worst = "";
        int worstIndex = -1, count = 0;
        JsonArray results = new JsonArray();
        try (EmotionModelRuntime runtime = new EmotionModelRuntime(Path.of(args[0]))) {
            for (JsonElement el : oracle.getAsJsonArray("cases")) {
                JsonObject c = el.getAsJsonObject();
                float[] features = gson.fromJson(c.get("features"), float[].class);
                float[] base = gson.fromJson(c.get("base"), float[].class);
                float[] expected = gson.fromJson(c.get("expected"), float[].class);
                float[] actual = runtime.predict(c.get("emotion").getAsInt(), c.get("genre").getAsInt(), features, base);
                double caseAll = 0, caseEffective = 0;
                for (int i = 0; i < 32; i++) {
                    double difference = Math.abs((double) expected[i] - actual[i]);
                    if (difference > allMax) { allMax = difference; worst = c.get("name").getAsString(); worstIndex = i; }
                    if (i < 19) { effectiveMax = Math.max(effectiveMax, difference); caseEffective = Math.max(caseEffective, difference); }
                    caseAll = Math.max(caseAll, difference); squared += difference * difference; count++;
                }
                JsonObject item = new JsonObject();
                item.add("name", c.get("name")); item.addProperty("maxAbsAll32", caseAll);
                item.addProperty("maxAbsEffective19", caseEffective); results.add(item);
            }
        }
        JsonObject report = new JsonObject();
        report.add("modelSha256", oracle.get("modelSha256")); report.add("oracleMnnVersion", oracle.get("mnnVersion"));
        report.addProperty("caseCount", results.size()); report.addProperty("maxAbsAll32", allMax);
        report.addProperty("maxAbsEffective19", effectiveMax); report.addProperty("rmseAll32", Math.sqrt(squared / count));
        report.addProperty("worstCase", worst); report.addProperty("worstIndex", worstIndex);
        report.add("cases", results);
        Files.writeString(Path.of(args[2]), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
        report.remove("cases"); System.out.println(report);
    }
}

package dev.dialogmenu;

import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** A YAML-defined menu family, with reusable catalog/list grids. No application IDs or business functions. */
final class MenuTemplate {
    record Entry(String label, String image, List<String> actions) {}
    record CollectionSpec(String load, String list, String status, String loading, String ready,
                          String write, String token, String result, int capacity, int timeout,
                          String empty, String editState, List<String> saved) {}
    static final class Definition {
        final String id;
        final ConfigurationSection variables;
        final Map<String, String> initial = new LinkedHashMap<>();
        final Map<String, Entry> entries = new LinkedHashMap<>();
        final Map<String, CollectionSpec> collections = new LinkedHashMap<>();
        final Map<String, String> messages = new LinkedHashMap<>();
        final List<Map<String, String>> samples = new ArrayList<>();
        final String characters;
        Definition(String id, YamlConfiguration yaml) {
            this.id = id;
            variables = yaml.getConfigurationSection("variables");
            var state = yaml.getConfigurationSection("state");
            if (state != null) for (String key : state.getKeys(false))
                initial.put(LayoutStore.id(key), state.getString(key, ""));
            var catalog = yaml.getConfigurationSection("entries");
            if (catalog != null) for (String key : catalog.getKeys(false)) {
                if (!key.matches("[a-zA-Z0-9:_-]+")) throw new IllegalArgumentException("entry ID非法：" + key);
                var e = Objects.requireNonNull(catalog.getConfigurationSection(key), "entry须为对象");
                LayoutStore.keys(e, Set.of("label", "image", "actions"));
                entries.put(key, new Entry(e.getString("label", key), e.getString("image", ""),
                    LayoutStore.actions(e.get("actions"), false)));
            }
            var sources = yaml.getConfigurationSection("collections");
            if (sources != null) for (String key : sources.getKeys(false)) {
                var s = Objects.requireNonNull(sources.getConfigurationSection(key), "collection须为对象");
                LayoutStore.keys(s, Set.of("load", "list", "status", "loading", "ready", "write", "token", "result",
                    "capacity", "timeout", "empty", "edit-state", "saved"));
                for (String field : List.of("load", "list", "status", "ready", "write", "token", "result"))
                    if (s.getString(field, "").isBlank()) throw new IllegalArgumentException(key + " 缺少" + field);
                String edit = s.getString("edit-state", "");
                if (!initial.containsKey(edit)) throw new IllegalArgumentException("edit-state须在state中定义");
                collections.put(LayoutStore.id(key), new CollectionSpec(s.getString("load"), s.getString("list"),
                    s.getString("status"), s.getString("loading", "loading"), s.getString("ready"),
                    s.getString("write"), s.getString("token"), s.getString("result"),
                    LayoutStore.integer(s, "capacity", 8, 1, 64), LayoutStore.integer(s, "timeout", 320, 1, 1200),
                    s.getString("empty", "none"), edit, LayoutStore.actions(s.get("saved"), false)));
            }
            if (variables != null) for (String key : variables.getKeys(false)) {
                var v = Objects.requireNonNull(variables.getConfigurationSection(key), "variable须为对象");
                LayoutStore.keys(v, Set.of("type", "source", "fallback", "preview", "max-length", "values"));
                if (!Set.of("papi", "skript", "state", "literal", "external").contains(v.getString("type", "")))
                    throw new IllegalArgumentException(key + " variable type非法");
                LayoutStore.integer(v, "max-length", 0, 0, 128);
            }
            var text = yaml.getConfigurationSection("messages");
            if (text != null) for (String key : text.getKeys(false)) messages.put(key, text.getString(key, ""));
            for (Map<?, ?> sample : yaml.getMapList("samples")) {
                Map<String, String> values = new LinkedHashMap<>();
                for (var entry : sample.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    if (variables == null || !variables.isConfigurationSection(key))
                        throw new IllegalArgumentException("sample variable未定义：" + key);
                    values.put(key, String.valueOf(entry.getValue()));
                }
                samples.add(Map.copyOf(values));
            }
            characters = yaml.saveToString();
        }
        String message(String key) { return messages.getOrDefault(key, key); }
        void changeState(Map<String, String> values, String action, String value) {
            String[] parts = value.split(" ", 2);
            if (action.equals("set") || action.equals("toggle")) {
                if (!values.containsKey(parts[0])) throw new IllegalArgumentException("state未定义：" + parts[0]);
            }
            switch (action) {
                case "toggle" -> values.put(parts[0], Boolean.toString(!Boolean.parseBoolean(values.get(parts[0]))));
                case "set" -> {
                    if (parts.length != 2) throw new IllegalArgumentException("set须为key value");
                    values.put(parts[0], parts[1]);
                }
                case "notice" -> values.put("notice", value);
                default -> throw new IllegalArgumentException("状态动作不支持：" + action);
            }
        }
        List<String> changed(String collection, List<String> before, String id) {
            CollectionSpec spec = Objects.requireNonNull(collections.get(collection), "collection不存在");
            List<String> next = new ArrayList<>(before);
            if (!next.remove(id)) {
                if (!entries.containsKey(id)) throw new IllegalArgumentException(message("unknown"));
                if (next.size() >= spec.capacity()) throw new IllegalArgumentException(message("full"));
                next.add(id);
            }
            return List.copyOf(next);
        }
        String preview(String text, Map<String, String> state, String player) {
            Map<String, String> values = new LinkedHashMap<>(initial);
            values.putAll(state);
            if (variables != null) for (String key : variables.getKeys(false)) {
                var v = variables.getConfigurationSection(key);
                String value = v.getString("preview", v.getString("fallback", ""));
                if (state.containsKey(key)) {
                    value = state.get(key);
                    var mapped = v.getConfigurationSection("values");
                    if (mapped != null) value = mapped.getString(value, value);
                }
                else if (v.getString("type", "").equals("state")) {
                    String source = values.getOrDefault(v.getString("source"), "");
                    var mapped = v.getConfigurationSection("values");
                    value = mapped == null ? source : mapped.getString(source, v.getString("fallback", ""));
                }
                else if (v.isConfigurationSection("values"))
                    value = v.getConfigurationSection("values").getString(value, v.getString("fallback", ""));
                values.put(key, value);
            }
            return expand(text, values).replace("{player}", player).replace("{uuid}", "preview");
        }
    }
    final Definition definition;
    final String page;
    private final String yamlText;
    private final List<ConfigurationSection> grids = new ArrayList<>();
    private MenuTemplate(Definition definition, String page, ConfigurationSection config) {
        this.definition = definition;
        this.page = page;
        YamlConfiguration yaml = new YamlConfiguration();
        copy(config, yaml);
        yaml.set("id", page);
        yaml.set("schema-version", 1);
        var section = yaml.getConfigurationSection("grids");
        if (section != null) for (String id : section.getKeys(false)) {
            var grid = Objects.requireNonNull(section.getConfigurationSection(id), "grid须为对象");
            LayoutStore.keys(grid, Set.of("collection", "items", "slots", "columns", "x", "y", "step-x", "step-y",
                "width", "height", "background", "icon-x", "icon-y", "icon-size", "text-x", "color",
                "empty-label", "empty-actions", "unknown-label"));
            if (!definition.collections.containsKey(grid.getString("collection")))
                throw new IllegalArgumentException("grid collection不存在：" + id);
            int slots = LayoutStore.integer(grid, "slots", 8, 1, 64);
            LayoutStore.integer(grid, "columns", 4, 1, 64);
            for (String field : List.of("width", "height", "step-x", "step-y", "icon-size"))
                if (LayoutStore.number(grid, field, 0) <= 0) throw new IllegalArgumentException("grid " + field + "须为正数");
            if (grid.getString("background", "").isBlank()) throw new IllegalArgumentException("grid缺少background");
            if (grid.contains("items")) {
                List<String> items = LayoutStore.strings(grid.get("items"));
                if (items.size() > slots) throw new IllegalArgumentException("grid items超过slots");
                for (String key : items) if (!definition.entries.containsKey(key))
                    throw new IllegalArgumentException("grid entry不存在：" + key);
            }
            LayoutStore.actions(grid.get("empty-actions"), false);
            grids.add(grid);
        }
        yaml.set("grids", null);
        yamlText = yaml.saveToString();
    }
    static Map<String, LayoutStore.Document> load(String id, YamlConfiguration yaml) throws Exception {
        LayoutStore.keys(yaml, Set.of("schema-version", "id", "variables", "state", "collections",
            "entries", "messages", "pages", "characters", "samples"));
        if (!id.equals(yaml.getString("id")) || LayoutStore.integer(yaml, "schema-version", 1, 1, 1) != 1)
            throw new IllegalArgumentException("应用id须与文件名一致");
        Definition definition = new Definition(id, yaml);
        Map<String, LayoutStore.Document> result = new LinkedHashMap<>();
        var pages = Objects.requireNonNull(yaml.getConfigurationSection("pages"), "缺少pages");
        for (String page : pages.getKeys(false)) {
            MenuTemplate template = new MenuTemplate(definition, LayoutStore.id(page),
                Objects.requireNonNull(pages.getConfigurationSection(page), "page须为对象"));
            result.put(page, template.document(Map.of()));
        }
        return result;
    }
    static void copy(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            if (source.isConfigurationSection(key)) copy(source.getConfigurationSection(key), target.createSection(key));
            else target.set(key, source.get(key));
        }
    }
    static String expand(String text, Map<String, String> values) {
        for (var e : values.entrySet()) text = text.replace("{" + e.getKey() + "}", e.getValue());
        return text;
    }
    LayoutStore.Document document(Map<String, List<String>> lists) throws Exception {
        return document(lists, text -> definition.preview(text, Map.of(), "Player"));
    }
    LayoutStore.Document document(Map<String, List<String>> lists,
                                  java.util.function.UnaryOperator<String> expand) throws Exception {
        return document(lists, expand, false);
    }
    private LayoutStore.Document document(Map<String, List<String>> lists,
                                  java.util.function.UnaryOperator<String> expand, boolean catalog) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString(yamlText);
        resolveSources(yaml.getConfigurationSection("frontend"), expand, catalog);
        var backend = yaml.getConfigurationSection("backend");
        if (backend != null) for (String key : new ArrayList<>(backend.getKeys(false))) {
            var region = backend.getConfigurationSection(key);
            if (region != null && !visible(region, expand, catalog)) backend.set(key, null);
            else if (region != null) resolveGeometry(region, expand);
        }
        var base = LayoutStore.parse(page, yaml);
        List<Node> nodes = new ArrayList<>(base.menu().nodes());
        List<Region> regions = new ArrayList<>(base.menu().regions());
        for (int g = 0; g < grids.size(); g++) {
            var grid = grids.get(g);
            String collection = grid.getString("collection");
            List<String> items = grid.contains("items") ? LayoutStore.strings(grid.get("items"))
                : lists.getOrDefault(collection, List.of());
            int slots = grid.getInt("slots", 8), columns = grid.getInt("columns", 4);
            for (int i = 0; i < slots; i++) {
                String id = i < items.size() ? items.get(i) : "";
                Entry entry = definition.entries.get(id);
                String prefix = "grid-" + g + "-" + i;
                double x = grid.getDouble("x") + i % columns * grid.getDouble("step-x");
                double y = grid.getDouble("y") - i / columns * grid.getDouble("step-y");
                String label = entry != null ? entry.label() : id.isEmpty() ? grid.getString("empty-label", "")
                    : grid.getString("unknown-label", id);
                double w = grid.getDouble("width"), h = grid.getDouble("height");
                nodes.add(image(prefix + "-base", grid.getString("background"), x, y, w, h));
                boolean icon = entry != null && !entry.image().isBlank();
                if (icon) nodes.add(image(prefix + "-icon", entry.image(), x + grid.getDouble("icon-x"),
                    y + grid.getDouble("icon-y"), grid.getDouble("icon-size"), grid.getDouble("icon-size")));
                String color = grid.getString("color", "#343537");
                if (!color.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("grid color非法");
                nodes.add(new Node(prefix + "-label", "text", icon ? x + grid.getDouble("text-x") : x, y,
                    1, 1, 1, 0, 0, 0xFF000000 | Integer.parseInt(color.substring(1), 16), label, "",
                    icon ? "left" : "center", 8, 1, 0, 9, 18, List.of()));
                List<String> actions = id.isEmpty() ? LayoutStore.actions(grid.get("empty-actions"), false)
                    : List.of("entry: " + collection + " " + id);
                regions.add(new Region(prefix, x, y, w, h, 0, "", List.of(label), actions));
            }
        }
        nodes.sort(Comparator.comparingDouble(Node::z));
        regions.sort(Comparator.comparingInt(Region::priority).reversed());
        return new LayoutStore.Document(new Menu(page, base.menu().permission(), base.menu().width(),
            base.menu().height(), List.copyOf(nodes), List.copyOf(regions), base.menu().closeWithEscape(),
            base.menu().scaleTextWithCanvas()), base.open(), base.close(), this);
    }
    private static boolean visible(ConfigurationSection node, java.util.function.UnaryOperator<String> expand,
                                   boolean catalog) {
        if (!node.contains("visible-if")) return true;
        Object condition = node.get("visible-if");
        if (!(condition instanceof String value)) throw new IllegalArgumentException("visible-if须为布尔变量字符串");
        String result = catalog ? "true" : expand.apply(value);
        if (!Set.of("true", "false").contains(result))
            throw new IllegalArgumentException("visible-if须解析为true/false：" + result);
        node.set("visible-if", null);
        return Boolean.parseBoolean(result);
    }
    private static void resolveSources(ConfigurationSection nodes, java.util.function.UnaryOperator<String> expand,
                                       boolean catalog) {
        if (nodes == null) return;
        for (String key : nodes.getKeys(false)) {
            var node = nodes.getConfigurationSection(key);
            if (node == null) continue;
            if (!visible(node, expand, catalog)) node.set("visible", false);
            if (node.getString("type", "").equals("image")) node.set("source", expand.apply(node.getString("source", "")));
            if (node.contains("color")) node.set("color", expand.apply(node.getString("color")));
            resolveGeometry(node, expand);
            resolveSources(node.getConfigurationSection("children"), expand, catalog);
        }
    }
    private static void resolveGeometry(ConfigurationSection node, java.util.function.UnaryOperator<String> expand) {
        for (String key : List.of("x", "y", "offset.x", "offset.y", "offset.z", "width", "height", "size", "line-width")) {
            if (!(node.get(key) instanceof String value)) continue;
            String resolved = expand.apply(value);
            try {
                double number = Double.parseDouble(resolved);
                if (!Double.isFinite(number)) throw new NumberFormatException();
                node.set(key, number);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(node.getCurrentPath() + "." + key + " 须解析为有限数字");
            }
        }
    }
    private static Node image(String id, String source, double x, double y, double w, double h) {
        return new Node(id, "image", x, y, 0, 1, 1, w, h, 0xFFFFFFFF, "", source,
            "center", 8, 1, 0, 9, 18, List.of());
    }
    List<LayoutStore.Document> variants() throws Exception {
        List<LayoutStore.Document> result = new ArrayList<>();
        result.add(document(Map.of(), text -> definition.preview(text, Map.of(), "Player"), true));
        Set<Map<String,String>> usedSamples = new HashSet<>();
        for (Map<String, String> sample : definition.samples) {
            Map<String,String> relevant = new LinkedHashMap<>();
            sample.forEach((key, value) -> { if (yamlText.contains("{" + key + "}")) relevant.put(key, value); });
            if (!relevant.isEmpty() && usedSamples.add(relevant))
                result.add(document(Map.of(), text -> definition.preview(text, relevant, "Player"), true));
        }
        for (var source : definition.collections.entrySet())
            for (String id : definition.entries.keySet())
                result.add(document(Map.of(source.getKey(), Collections.nCopies(source.getValue().capacity(), id)),
                    text -> definition.preview(text, Map.of(), "Player"), true));
        if (definition.variables != null) for (String key : definition.variables.getKeys(false)) {
            var variable = definition.variables.getConfigurationSection(key);
            var choices = variable.getConfigurationSection("values");
            if (choices != null) for (String value : new LinkedHashSet<>(choices.getValues(false).values().stream()
                .map(String::valueOf).toList()))
                result.add(document(Map.of(), text -> definition.preview(text, Map.of(key, value), "Player"), true));
        }
        return result;
    }
    static List<LayoutStore.Document> catalog(Collection<LayoutStore.Document> docs) throws Exception {
        List<LayoutStore.Document> result = new ArrayList<>();
        for (var doc : docs) {
            if (doc.template() == null) result.add(doc);
            else result.addAll(doc.template().variants());
        }
        return result;
    }
    static String characters(Collection<LayoutStore.Document> docs) {
        StringBuilder text = new StringBuilder();
        Set<Definition> visited = new HashSet<>();
        for (var doc : docs) if (doc.template() != null && visited.add(doc.template().definition))
            text.append(doc.template().definition.characters);
        return text.toString();
    }
    static Context preview(LayoutStore.Document doc, Context base) {
        if (doc.template() == null) return base;
        return new Context() {
            public String expand(String text) {
                return base.expand(doc.template().definition.preview(text, Map.of(), "{player}"));
            }
            public Icon item(int slot) { return base.item(slot); }
            public boolean permitted(String permission) { return base.permitted(permission); }
            public double textScale() { return base.textScale(); }
        };
    }
}

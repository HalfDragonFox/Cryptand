package com.hdf.cryptand.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JSON Schema 构建助手（{@code tools/list} 的 {@code inputSchema}）。
 *
 * <p>两个用途：</p>
 * <ol>
 *   <li>{@link Builder} —— 新工具直接写规范 schema；</li>
 *   <li>{@link #fromLegacyPairs(JsonObject)} —— 把 Cryptand 老式参数表
 *       （{@code {"x":"int/string（支持 ~）"}}）<b>自动翻译</b>成标准 JSON Schema，
 *       让既有工具零改动即可被真正的 MCP 客户端发现（客户端要按 schema 校验参数）。</li>
 * </ol>
 */
public final class ToolSchemas {

    private ToolSchemas() {
    }

    /** 空参数 schema（无参工具） */
    public static JsonObject object() {
        final JsonObject o = new JsonObject();
        o.addProperty("type", "object");
        o.add("properties", new JsonObject());
        return o;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 参数 schema 构建器（链式） */
    public static final class Builder {

        private final JsonObject properties = new JsonObject();
        private final JsonArray required = new JsonArray();

        public Builder prop(String name, JsonObject schema) {
            properties.add(name, schema);
            return this;
        }

        public Builder str(String name, String description) {
            return prop(name, primitive("string", description));
        }

        public Builder integer(String name, String description) {
            return prop(name, primitive("integer", description));
        }

        public Builder number(String name, String description) {
            return prop(name, primitive("number", description));
        }

        public Builder bool(String name, String description) {
            return prop(name, primitive("boolean", description));
        }

        /** int 或 string（兼容 "~" 相对坐标这类双形态参数） */
        public Builder intOrString(String name, String description) {
            final JsonObject o = new JsonObject();
            final JsonArray any = new JsonArray();
            any.add(primitive("integer", null));
            any.add(primitive("string", null));
            o.add("anyOf", any);
            o.addProperty("description", description == null ? "" : description);
            return prop(name, o);
        }

        public Builder array(String name, String description) {
            final JsonObject o = new JsonObject();
            o.addProperty("type", "array");
            o.add("items", new JsonObject());
            o.addProperty("description", description == null ? "" : description);
            return prop(name, o);
        }

        public Builder require(String name) {
            required.add(name);
            return this;
        }

        public JsonObject build() {
            final JsonObject o = new JsonObject();
            o.addProperty("type", "object");
            o.add("properties", properties);
            if (required.size() > 0) {
                o.add("required", required);
            }
            return o;
        }
    }

    private static JsonObject primitive(String type, String description) {
        final JsonObject o = new JsonObject();
        o.addProperty("type", type);
        if (description != null && !description.isBlank()) {
            o.addProperty("description", description);
        }
        return o;
    }

    // ==================== 老式参数表 → JSON Schema ====================

    /**
     * 把老式参数表翻译成标准 JSON Schema。
     *
     * <p>老式形态：{@code schema("x", "int/string（支持 ~）", "block", "string（简名 stone）")}
     * → {@code {"x":"int/string（支持 ~）","block":"string（简名 stone）"}}。
     * 翻译结果保留<b>原文作为 description</b>（模型能看到完整说明），类型则解析成
     * {@code type} 或 {@code anyOf}。</p>
     */
    public static JsonObject fromLegacyPairs(JsonObject pairs) {
        final JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        final JsonObject props = new JsonObject();
        if (pairs != null) {
            for (Map.Entry<String, com.google.gson.JsonElement> e : pairs.entrySet()) {
                final String spec = e.getValue() == null || e.getValue().isJsonNull()
                        ? "" : e.getValue().getAsString();
                props.add(e.getKey(), fromLegacySpec(spec));
            }
        }
        root.add("properties", props);
        return root;
    }

    /** 单条老式参数说明 → schema 片段 */
    public static JsonObject fromLegacySpec(String spec) {
        final String raw = spec == null ? "" : spec.trim();
        final JsonObject out = new JsonObject();
        if (!raw.isEmpty()) {
            out.addProperty("description", raw);
        }
        final String head = headOf(raw);
        final Set<String> types = typesOf(head);
        if (types.size() == 1) {
            out.addProperty("type", types.iterator().next());
        } else if (types.size() > 1) {
            final JsonArray any = new JsonArray();
            for (String t : types) {
                final JsonObject one = new JsonObject();
                one.addProperty("type", t);
                any.add(one);
            }
            out.add("anyOf", any);
        }
        // 解析不出类型时保持宽松（任何类型都收）—— 由工具自己校验参数
        return out;
    }

    /** 去掉中文/英文括号里的补充说明，只留类型区 */
    private static String headOf(String raw) {
        int cut = -1;
        for (String open : new String[]{"（", "("}) {
            final int i = raw.indexOf(open);
            if (i >= 0 && (cut < 0 || i < cut)) {
                cut = i;
            }
        }
        return cut < 0 ? raw : raw.substring(0, cut);
    }

    private static Set<String> typesOf(String head) {
        final Set<String> types = new LinkedHashSet<>();
        for (String token : head.split("[/、,，|·；;\\s]+")) {
            final String t = token.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) {
                continue;
            }
            switch (t) {
                case "int", "integer", "整数" -> types.add("integer");
                case "string", "str", "字符串", "文本", "名字" -> types.add("string");
                case "double", "float", "number", "数字", "小数", "浮点" -> types.add("number");
                case "bool", "boolean", "布尔", "开关" -> types.add("boolean");
                case "array", "数组", "列表", "list" -> types.add("array");
                case "object", "对象", "映射", "map", "dict" -> types.add("object");
                default -> {
                    // 不认识的类型词（如"两种写法都收"）→ 宽松处理，不猜
                }
            }
        }
        return types;
    }

    // ==================== 注解 ====================

    /**
     * MCP 工具注解（提示客户端/模型"这个工具会不会改世界"）。
     *
     * @param readOnly    只读（不影响任何状态）
     * @param destructive 可能造成破坏性/不可逆修改
     * @param idempotent  重复调用结果一致
     * @param openWorld   与外部世界交互（外部实体/网络）
     */
    public static JsonObject annotations(boolean readOnly, boolean destructive,
                                         boolean idempotent, boolean openWorld) {
        final JsonObject o = new JsonObject();
        o.addProperty("readOnlyHint", readOnly);
        o.addProperty("destructiveHint", destructive);
        o.addProperty("idempotentHint", idempotent);
        o.addProperty("openWorldHint", openWorld);
        return o;
    }

    /** 工具名合法性（MCP 建议：字母数字与 _ - . /，长度 1..128） */
    public static boolean validToolName(String name) {
        if (name == null || name.isBlank() || name.length() > 128) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            final boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.' || c == '/';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}

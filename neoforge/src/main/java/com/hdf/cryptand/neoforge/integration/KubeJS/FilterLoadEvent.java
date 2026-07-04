//package com.hdf.cryptand.neoforge.integration.KubeJS;
//
//import com.google.gson.JsonElement;
//import dev.latvian.mods.kubejs.event.EventJS;
//import net.minecraft.resources.ResourceKey;
//
//public class FilterLoadEvent extends EventJS {
//    private final ResourceKey<?> registryKey;
//    private JsonElement json;
//    private boolean jsonModified = false;
//    private boolean skipDefault = false;
//
//    public FilterLoadEvent(JsonElement original, ResourceKey<?> registryKey) {
//        this.json = original;
//        this.registryKey = registryKey;
//    }
//
//    public JsonElement getJson() {
//        return json;
//    }
//
//    public void setJson(JsonElement newJson) {
//        this.json = newJson;
//        this.jsonModified = true;
//    }
//
//    public ResourceKey<?> getRegistryKey() {
//        return registryKey;
//    }
//
//    public String getRegistryName() {
//        return registryKey.location().toString();
//    }
//
//    public void skipDefault() {
//        this.skipDefault = true;
//    }
//
//    public boolean shouldSkipDefault() {
//        return skipDefault;
//    }
//
//    public boolean isJsonModified() {
//        return jsonModified;
//    }
//}

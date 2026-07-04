//package com.hdf.cryptand.neoforge.integration.KubeJS;
//
//import dev.latvian.mods.kubejs.KubeJSPlugin;
//import dev.latvian.mods.kubejs.event.EventGroup;
//import dev.latvian.mods.kubejs.event.EventHandler;
//import dev.latvian.mods.kubejs.script.BindingsEvent;
//import com.hdf.cryptand.core.FilterProcessor.Advanced.AdvancedFilterProcessorRegistry;
//import com.hdf.cryptand.core.FilterProcessor.Base.FilterProcessorRegistry;
//
//public class CryptandPlugin extends KubeJSPlugin {
//    // 定义事件组，脚本中通过 "CryptandEvents" 访问
//    public static final EventGroup WAF_KUBEJS_GROUP = EventGroup.of("CryptandLoadEvents");
//
//    public static final EventHandler ON_FILTER_INIT_GLOBAL = WAF_KUBEJS_GROUP.server("onFilterInitGlobal", () -> FilterLoadEvent.class);
//    public static final EventHandler ON_FILTER_LOAD_GLOBAL = WAF_KUBEJS_GROUP.server("onFilterLoadGlobal", () -> FilterLoadEvent.class);
//
//    @Override
//    public void registerEvents() {
//        WAF_KUBEJS_GROUP.register();
//    }
//
//    @Override
//    public void registerBindings(BindingsEvent event) {
//        event.add("CryptandFilterBase", FilterProcessorRegistry.instance());
//        event.add("CryptandFilterAdvanced", AdvancedFilterProcessorRegistry.instance());
//    }
//}

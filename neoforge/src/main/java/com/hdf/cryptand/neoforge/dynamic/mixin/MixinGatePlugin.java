package com.hdf.cryptand.neoforge.dynamic.mixin;

import com.hdf.cryptand.dynamic.api.DynamicPlugin;

import java.util.List;

/**
 * mixin 门控插件：声明"哪个 modid 的哪些 mixin 允许注入"。
 *
 * <p>这是"向 mixin 核心申请注册"的插件形态：加载后该 modid 即进入管辖，且默认白名单生效。</p>
 */
public interface MixinGatePlugin extends DynamicPlugin {

    /** 要纳入管辖的 modid（例如自己的 modid）。 */
    String mixinModId();

    /** 允许注入的 mixin 全限定类名（白名单）。 */
    List<String> allowedMixins();
}

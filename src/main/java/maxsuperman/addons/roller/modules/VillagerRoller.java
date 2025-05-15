package maxsuperman.addons.roller.modules;

import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.objects.ObjectIntImmutablePair;
import maxsuperman.addons.roller.gui.screens.EnchantmentSelectScreen;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.InteractEntityEvent;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WIntEdit;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.ISerializable;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.EnchantmentLevelEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.SetTradeOffersS2CPacket;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.EnchantmentTags;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.VillagerProfession;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class VillagerRoller extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSound = settings.createGroup("声音设置");
    private final SettingGroup sgChatFeddback = settings.createGroup("聊天反馈", false);

    private final Setting<Boolean> disableIfFound = sgGeneral.add(new BoolSetting.Builder()
        .name("找到后禁用")
        .description("如果找到列表中的附魔则禁用")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disconnectIfFound = sgGeneral.add(new BoolSetting.Builder()
        .name("找到后断开连接")
        .description("如果找到列表中的附魔则断开与服务器的连接")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> saveListToConfig = sgGeneral.add(new BoolSetting.Builder()
        .name("保存列表到配置")
        .description("启用或禁用将滚动列表保存到配置和剪贴板缓冲区")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enablePlaySound = sgGeneral.add(new BoolSetting.Builder()
        .name("启用声音")
        .description("找到所需交易时播放声音")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<SoundEvent>> sound = sgSound.add(new SoundEventListSetting.Builder()
        .name("播放的声音")
        .description("如果启用，找到所需交易时将播放的声音")
        .defaultValue(Collections.singletonList(SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK))
        .build()
    );

    private final Setting<Double> soundPitch = sgSound.add(new DoubleSetting.Builder()
        .name("声音音调")
        .description("播放声音的音调")
        .defaultValue(1.0)
        .min(0)
        .sliderRange(0, 8)
        .build()
    );

    private final Setting<Double> soundVolume = sgSound.add(new DoubleSetting.Builder()
        .name("声音音量")
        .description("播放声音的音量")
        .defaultValue(1.0)
        .min(0)
        .sliderRange(0, 1)
        .build()
    );

    private final Setting<Boolean> pauseOnScreen = sgGeneral.add(new BoolSetting.Builder()
        .name("在屏幕上暂停")
        .description("如果打开任何屏幕，则暂停滚动")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> headRotateOnPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("放置时旋转头部")
        .description("放置方块时是否看向方块？")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> failedToPlaceDelay = sgGeneral.add(new IntSetting.Builder()
        .name("放置失败延迟")
        .description("放置方块失败后的延迟（毫秒）")
        .defaultValue(1500)
        .min(0)
        .sliderRange(0, 10000)
        .build()
    );

    private final Setting<Boolean> failedToPlaceDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("放置失败时禁用")
        .description("如果放置方块失败，则禁用滚动器")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> maxProfessionWaitTime = sgGeneral.add(new IntSetting.Builder()
        .name("最大职业等待时间")
        .description("如果村民未获得职业，则等待的时间（毫秒）。零 = 无限。")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 10000)
        .build()
    );

    private final Setting<Boolean> onlyTradable = sgGeneral.add(new BoolSetting.Builder()
        .name("仅可交易")
        .description("隐藏未标记为可交易的附魔")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> sortEnchantments = sgGeneral.add(new BoolSetting.Builder()
        .name("排序附魔")
        .description("按名称排序显示附魔")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfSetup = sgGeneral.add(new BoolSetting.Builder()
        .name("设置提示")
        .description("开始时提示该做什么（否则在模块列表状态中表示）")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfPausedOnScreen = sgGeneral.add(new BoolSetting.Builder()
        .name("在屏幕上暂停")
        .description("滚动暂停，与村民互动以继续")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfLowerLevel = sgGeneral.add(new BoolSetting.Builder()
        .name("找到较低等级")
        .description("找到附魔 %s 但不是最高等级：%d (最大) > %d (找到)")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfTooExpensive = sgGeneral.add(new BoolSetting.Builder()
        .name("找到太贵")
        .description("找到附魔 %s 但价格太高：%s (最大价格) < %d (成本)")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfIgnored = sgGeneral.add(new BoolSetting.Builder()
        .name("找到不在列表中")
        .description("找到附魔 %s 但不在列表中。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfProfessionTimeout = sgGeneral.add(new BoolSetting.Builder()
        .name("职业超时")
        .description("村民未在指定时间内获得职业")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfPlaceFailed = sgGeneral.add(new BoolSetting.Builder()
        .name("放置失败")
        .description("放置失败，无法放置或无法将讲台放入快捷栏（它们仍然会触发放置失败的设置）")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfDiscrepancy = sgGeneral.add(new BoolSetting.Builder()
        .name("不一致")
        .description("滚动器意外进入某种状态（可能是反作弊干扰）")
        .defaultValue(true)
        .build()
    );



    private enum State {
        DISABLED,
        WAITING_FOR_TARGET_BLOCK,
        WAITING_FOR_TARGET_VILLAGER,
        ROLLING_BREAKING_BLOCK,
        ROLLING_WAITING_FOR_VILLAGER_PROFESSION_CLEAR,
        ROLLING_PLACING_BLOCK,
        ROLLING_WAITING_FOR_VILLAGER_PROFESSION_NEW,
        ROLLING_WAITING_FOR_VILLAGER_TRADES
    }

    private static final Path CONFIG_PATH = MeteorClient.FOLDER.toPath().resolve("VillagerRoller");
    private State currentState = State.DISABLED;
    private VillagerEntity rollingVillager;
    private BlockPos rollingBlockPos;
    private Block rollingBlock;
    private final List<RollingEnchantment> searchingEnchants = new ArrayList<>();
    private long failedToPlacePrevMsg = System.currentTimeMillis();
    private long currentProfessionWaitTime;

    public VillagerRoller() {
        super(Categories.Misc, "自动村民交易刷新", "自动刷新村民交易");
    }

    @Override
    public void onActivate() {
        if (toggleOnBindRelease) {
            toggleOnBindRelease = false;
            if (cfSetup.get()) {
                info("您已将 'Toggle on bind release' 设置为 true，我已将其关闭以避免一些故障排除");
            }
        }
        currentState = State.WAITING_FOR_TARGET_BLOCK;
        if (cfSetup.get()) {
            info("攻击您想要滚动的方块");
        }
    }

    @Override
    public void onDeactivate() {
        currentState = State.DISABLED;
    }

    @Override
    public String getInfoString() {
        return currentState.toString();
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = super.toTag();
        if (saveListToConfig.get()) {
            NbtList l = new NbtList();
            for (RollingEnchantment e : searchingEnchants) {
                l.add(e.toTag());
            }
            tag.put("rolling", l);
        }
        return tag;
    }

    @Override
    public Module fromTag(NbtCompound tag) {
        super.fromTag(tag);
        if (saveListToConfig.get()) {
            NbtList l = tag.getList("rolling", NbtElement.COMPOUND_TYPE);
            searchingEnchants.clear();
            for (NbtElement e : l) {
                if (e.getType() != NbtElement.COMPOUND_TYPE) {
                    info("Invalid list element");
                    continue;
                }
                searchingEnchants.add(new RollingEnchantment().fromTag((NbtCompound) e));
            }
        }
        return this;
    }

    private boolean loadSearchingFromFile(File f) {
        if (!f.exists() || !f.canRead()) {
            error("文件不存在或无法加载");
            return false;
        }
        NbtCompound r = null;
        try {
            r = NbtIo.read(f.toPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (r == null) {
            error("从文件加载NBT失败");
            return false;
        }
        NbtList l = r.getList("rolling", NbtElement.COMPOUND_TYPE);
        searchingEnchants.clear();
        for (NbtElement e : l) {
            if (e.getType() != NbtElement.COMPOUND_TYPE) {
                info("无效的列表元素");
                continue;
            }
            searchingEnchants.add(new RollingEnchantment().fromTag((NbtCompound) e));
        }
        return true;
    }

    public boolean saveSearchingToFile(File f) {
        NbtList l = new NbtList();
        for (RollingEnchantment e : searchingEnchants) {
            l.add(e.toTag());
        }
        NbtCompound c = new NbtCompound();
        c.put("rolling", l);
        if (Files.notExists(f.getParentFile().toPath()) && !f.getParentFile().mkdirs()) {
            error("创建目录失败");
            return false;
        }
        try {
            NbtIo.write(c, f.toPath());
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        fillWidget(theme, list);
        return list;
    }

    private void fillWidget(GuiTheme theme, WVerticalList list) {
        WSection loadDataSection = list.add(theme.section("配置保存")).expandX().widget();

        WTable control = loadDataSection.add(theme.table()).expandX().widget();

        WTextBox savedConfigName = control.add(theme.textBox("未命名")).expandWidgetX().expandCellX().expandX().widget();
        WButton save = control.add(theme.button("保存")).expandX().widget();
        save.action = () -> {
            if (saveSearchingToFile(new File(new File(MeteorClient.FOLDER, "VillagerRoller"), savedConfigName.get() + ".nbt"))) {
                info("保存成功");
            } else {
                error("保存失败");
            }
            list.clear();
            fillWidget(theme, list);
        };
        control.row();

        ArrayList<String> configs = new ArrayList<>();
        if (Files.notExists(CONFIG_PATH)) {
            if (!CONFIG_PATH.toFile().mkdirs()) error("创建目录失败 [{}]", CONFIG_PATH);
        } else {
            try (DirectoryStream<Path> configDir = Files.newDirectoryStream(CONFIG_PATH)) {
                for (Path config : configDir) {
                    configs.add(FilenameUtils.removeExtension(config.getFileName().toString()));
                }
            } catch (IOException e) {
                error("列出目录失败", e);
            }
        }
        if (!configs.isEmpty()) {
            WDropdown<String> loadedConfigName = control.add(theme.dropdown(configs.toArray(new String[0]), "default")).expandWidgetX().expandCellX().expandX().widget();
            WButton load = control.add(theme.button("加载")).expandX().widget();
            load.action = () -> {
                if (loadSearchingFromFile(new File(new File(MeteorClient.FOLDER, "VillagerRoller"), loadedConfigName.get() + ".nbt"))) {
                    list.clear();
                    fillWidget(theme, list);
                    info("加载成功");
                } else {
                    error("加载文件失败");
                }
            };
        }

        WSection enchantments = list.add(theme.section("附魔")).expandX().widget();

        WTable table = enchantments.add(theme.table()).expandX().widget();
        table.add(theme.item(Items.BOOK.getDefaultStack()));
        table.add(theme.label("附魔"));
        table.add(theme.label("等级"));
        table.add(theme.label("价格"));
        table.add(theme.label("启用"));
        table.add(theme.label("移除"));
        table.row();
        if (sortEnchantments.get()) {
            searchingEnchants.removeIf(ench -> ench.enchantment == null);
            searchingEnchants.sort(Comparator.comparing(o -> o.enchantment));
        }

        Optional<Registry<Enchantment>> reg;
        if (mc.world != null) {
            reg = mc.world.getRegistryManager().getOptional(RegistryKeys.ENCHANTMENT);
        } else {
            reg = Optional.empty();
        }

        for (int i = 0; i < searchingEnchants.size(); i++) {
            RollingEnchantment e = searchingEnchants.get(i);
            Optional<RegistryEntry.Reference<Enchantment>> en;
            if (reg.isPresent()) {
                en = reg.get().getEntry(e.enchantment);
            } else {
                en = Optional.empty();
            }
            final int si = i;
            ItemStack book = Items.ENCHANTED_BOOK.getDefaultStack();
            int maxlevel = 255;
            if (en.isPresent()) {
                book = EnchantmentHelper.getEnchantedBookWith(new EnchantmentLevelEntry(en.get(), en.get().value().getMaxLevel()));
                maxlevel = en.get().value().getMaxLevel();
            }
            table.add(theme.item(book));

            WHorizontalList label = theme.horizontalList();
            WButton c = label.add(theme.button("更改")).widget();
            c.action = () -> mc.setScreen(new EnchantmentSelectScreen(theme, onlyTradable.get(), sel -> {
                searchingEnchants.set(si, sel);
                list.clear();
                fillWidget(theme, list);
            }));
            if (en.isPresent()) {
                label.add(theme.label(Names.get(en.get())));
            } else {
                label.add(theme.label(e.enchantment.toString()));
            }
            table.add(label);

            WIntEdit lev = table.add(theme.intEdit(e.minLevel, 0, maxlevel, true)).minWidth(40).expandX().widget();
            lev.action = () -> e.minLevel = lev.get();
            lev.tooltip = "最小附魔等级，0 表示仅最大可能等级（对于自定义，0 表示 1）";

            WHorizontalList costbox = table.add(theme.horizontalList()).minWidth(50).expandX().widget();
            WIntEdit cost = costbox.add(theme.intEdit(e.maxCost, 0, 64, false)).minWidth(40).expandX().widget();
            cost.action = () -> e.maxCost = cost.get();
            cost.tooltip = "最大价格（绿宝石），0 表示无限制";

            WButton setOptimal = costbox.add(theme.button("O")).widget();
            setOptimal.tooltip = "设置为最优价格 (2 + 最大等级*3)（如果是宝藏则翻倍）（如果已知）";
            setOptimal.action = () -> {
                list.clear();
                en.ifPresent(enchantmentReference -> e.maxCost = getMinimumPrice(enchantmentReference));
                fillWidget(theme, list);
            };

            WCheckbox enabled = table.add(theme.checkbox(e.enabled)).widget();
            enabled.action = () -> e.enabled = enabled.checked;
            enabled.tooltip = "启用？";

            WMinus del = table.add(theme.minus()).widget();
            del.action = () -> {
                list.clear();
                searchingEnchants.remove(e);
                fillWidget(theme, list);
            };
            table.row();
        }

        WTable controls = list.add(theme.table()).expandX().widget();

        WButton removeAll = controls.add(theme.button("移除所有")).expandX().widget();
        removeAll.action = () -> {
            list.clear();
            searchingEnchants.clear();
            fillWidget(theme, list);
        };

        WButton add = controls.add(theme.button("添加")).expandX().widget();
        add.action = () -> mc.setScreen(new EnchantmentSelectScreen(theme, onlyTradable.get(), e -> {
            e.minLevel = 1;
            e.maxCost = 64;
            e.enabled = true;
            searchingEnchants.add(e);
            list.clear();
            fillWidget(theme, list);
        }));

        WButton addAll = controls.add(theme.button("添加所有")).expandX().widget();
        addAll.action = () -> {
            list.clear();
            searchingEnchants.clear();
            if (reg.isPresent()) {
                for (RegistryEntry<Enchantment> e : getEnchants(onlyTradable.get())) {
                    searchingEnchants.add(new RollingEnchantment(reg.get().getId(e.value()), e.value().getMaxLevel(), getMinimumPrice(e), true));
                }
            }
            fillWidget(theme, list);
        };
        controls.row();

        WButton setOptimalForAll = controls.add(theme.button("为所有设置最优价格")).expandX().widget();
        setOptimalForAll.action = () -> {
            list.clear();
            if (reg.isPresent()) {
                for (RollingEnchantment e : searchingEnchants) {
                    reg.get().getEntry(e.enchantment).ifPresent(enchantmentReference -> e.maxCost = getMinimumPrice(enchantmentReference));
                }
            }
            fillWidget(theme, list);
        };

        WButton priceBumpUp = controls.add(theme.button("为所有价格加1")).expandX().widget();
        priceBumpUp.action = () -> {
            list.clear();
            for (RollingEnchantment e : searchingEnchants) {
                if (e.maxCost < 64) e.maxCost++;
            }
            fillWidget(theme, list);
        };

        WButton priceBumpDown = controls.add(theme.button("为所有价格减1")).expandX().widget();
        priceBumpDown.action = () -> {
            list.clear();
            for (RollingEnchantment e : searchingEnchants) {
                if (e.maxCost > 0) e.maxCost--;
            }
            fillWidget(theme, list);
        };
        controls.row();

        WButton setZeroForAll = controls.add(theme.button("为所有设置零价格")).expandX().widget();
        setZeroForAll.action = () -> {
            list.clear();
            for (RollingEnchantment e : searchingEnchants) {
                e.maxCost = 0;
            }
            fillWidget(theme, list);
        };

        WButton enableAll = controls.add(theme.button("启用所有")).expandX().widget();
        enableAll.action = () -> {
            list.clear();
            for (RollingEnchantment e : searchingEnchants) {
                e.enabled = true;
            }
            fillWidget(theme, list);
        };

        WButton disableAll = controls.add(theme.button("禁用所有")).expandX().widget();
        disableAll.action = () -> {
            list.clear();
            for (RollingEnchantment e : searchingEnchants) {
                e.enabled = false;
            }
            fillWidget(theme, list);
        };
        controls.row();

    }

    public List<RegistryEntry<Enchantment>> getEnchants(boolean onlyTradable) {
        if (mc.world == null) {
            return Collections.emptyList();
        }
        var reg = mc.world.getRegistryManager().getOptional(RegistryKeys.ENCHANTMENT);
        if (reg.isEmpty()) {
            return Collections.emptyList();
        }
        List<RegistryEntry<Enchantment>> available = new ArrayList<>();
        if (onlyTradable) {
            var i = reg.get().iterateEntries(EnchantmentTags.TRADEABLE);
            i.iterator().forEachRemaining(available::add);
            return available;
        } else {
            for (var a : reg.get().getIndexedEntries()) {
                available.add(a);
            }
            return available;
        }
    }

    public static int getMinimumPrice(RegistryEntry<Enchantment> e) {
        if (e == null) return 0;
        return e.isIn(EnchantmentTags.DOUBLE_TRADE_PRICE) ? (2 + 3 * e.value().getMaxLevel()) * 2 : 2 + 3 * e.value().getMaxLevel();
    }

    public void triggerInteract() {
        if (pauseOnScreen.get() && mc.currentScreen != null) {
            if (cfPausedOnScreen.get()) {
                info("滚动暂停，与村民互动以继续");
            }
        } else {
            Vec3d playerPos = mc.player.getEyePos();
            Vec3d villagerPos = rollingVillager.getEyePos();
            EntityHitResult entityHitResult = ProjectileUtil.raycast(mc.player, playerPos, villagerPos, rollingVillager.getBoundingBox(), Entity::canHit, playerPos.squaredDistanceTo(villagerPos));
            if (entityHitResult == null) {
                // Raycast didn't find villager entity?
                mc.interactionManager.interactEntity(mc.player, rollingVillager, Hand.MAIN_HAND);
            } else {
                ActionResult actionResult = mc.interactionManager.interactEntityAtLocation(mc.player, rollingVillager, entityHitResult, Hand.MAIN_HAND);
                if (!actionResult.isAccepted()) {
                    mc.interactionManager.interactEntity(mc.player, rollingVillager, Hand.MAIN_HAND);
                }
            }
        }
    }

    public List<Pair<RegistryEntry<Enchantment>, Integer>> getEnchants(ItemStack stack) {
        List<Pair<RegistryEntry<Enchantment>, Integer>> ret = new ArrayList<>();
        for (var e : EnchantmentHelper.getEnchantments(stack).getEnchantmentEntries()) {
            ret.add(ObjectIntImmutablePair.of(e.getKey(), e.getIntValue()));
        }
        return ret;
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (currentState != State.ROLLING_WAITING_FOR_VILLAGER_TRADES) return;
        if (!(event.packet instanceof SetTradeOffersS2CPacket p)) return;
        mc.executeSync(() -> triggerTradeCheck(p.getOffers()));
    }

    public void triggerTradeCheck(TradeOfferList l) {
        for (TradeOffer offer : l) {
            ItemStack sellItem = offer.getSellItem();
            if (!sellItem.isOf(Items.ENCHANTED_BOOK) || sellItem.get(DataComponentTypes.STORED_ENCHANTMENTS) == null)
                break;

            for (Pair<RegistryEntry<Enchantment>, Integer> enchant : getEnchants(sellItem)) {
                int enchantLevel = enchant.right();
                var reg = mc.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
                String enchantIdString = reg.getId(enchant.key().value()).toString();
                String enchantName = Names.get(enchant.key());

                boolean found = false;
                for (RollingEnchantment e : searchingEnchants) {
                    if (!e.enabled || !e.enchantment.toString().equals(enchantIdString)) continue;
                    found = true;
                    if (e.minLevel <= 0) {
                        int ml = enchant.key().value().getMaxLevel();
                        if (enchantLevel < ml) {
                            if (cfLowerLevel.get()) {
                                info(String.format("找到附魔 %s 但不是最高等级：%d (最大) > %d (找到)",
                                    enchantName, ml, enchantLevel));
                            }
                            continue;
                        }
                    } else if (e.minLevel > enchantLevel) {
                        if (cfLowerLevel.get()) {
                            info(String.format("找到附魔 %s 但等级太低：%d (要求等级) > %d (滚动等级)",
                                enchantName, e.minLevel, enchantLevel));
                        }
                        continue;
                    }
                    if (e.maxCost > 0 && offer.getOriginalFirstBuyItem().getCount() > e.maxCost) {
                        if (cfTooExpensive.get()) {
                            info(String.format("找到附魔 %s 但价格太高：%s (最大价格) < %d (成本)",
                                enchantName, e.maxCost, offer.getOriginalFirstBuyItem().getCount()));
                        }
                        continue;
                    }
                    if (disableIfFound.get()) e.enabled = false;
                    toggle();
                    if (enablePlaySound.get() && !sound.get().isEmpty()) {
                        mc.getSoundManager().play(PositionedSoundInstance.master(sound.get().get(0),
                            soundPitch.get().floatValue(), soundVolume.get().floatValue()));
                    }
                    if (disconnectIfFound.get()) {
                        MutableText text = Text.literal(String.format("[VillagerRoller] 找到附魔 %s 价格为 %d 绿宝石并自动断开连接。", enchantName, offer.getOriginalFirstBuyItem().getCount()));
                        mc.player.networkHandler.onDisconnect(new DisconnectS2CPacket(text));
                    }
                    break;
                }
                if (!found && cfIgnored.get()) {
                    info(String.format("找到附魔 %s 但不在列表中。", enchantName));
                }
            }
        }

        mc.player.closeHandledScreen();
        currentState = State.ROLLING_BREAKING_BLOCK;
    }

    @EventHandler
    private void onInteractEntity(InteractEntityEvent event) {
        if (currentState != State.WAITING_FOR_TARGET_VILLAGER) return;
        if (!(event.entity instanceof VillagerEntity villager)) return;

        rollingVillager = villager;
        currentState = State.ROLLING_BREAKING_BLOCK;
        if (cfSetup.get()) {
            info("已选择村民");
        }
        event.cancel();
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onStartBreakingBlockEvent(StartBreakingBlockEvent event) {
        if (currentState != State.WAITING_FOR_TARGET_BLOCK) return;

        rollingBlockPos = event.blockPos;
        rollingBlock = mc.world.getBlockState(rollingBlockPos).getBlock();
        currentState = State.WAITING_FOR_TARGET_VILLAGER;
        if (cfSetup.get()) {
            info("已选择滚动方块，现在与您想要滚动的村民互动");
        }
    }

    private void placeFailed(String msg) {
        if (failedToPlacePrevMsg + failedToPlaceDelay.get() <= System.currentTimeMillis()) {
            if (cfPlaceFailed.get()) {
                info(msg);
            }
            failedToPlacePrevMsg = System.currentTimeMillis();
        }
        if (failedToPlaceDisable.get()) toggle();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        switch (currentState) {
            case ROLLING_BREAKING_BLOCK -> {
                if (mc.world.getBlockState(rollingBlockPos) == Blocks.AIR.getDefaultState()) {
                    // info("方块已被破坏，等待村民清除职业...");
                    currentState = State.ROLLING_WAITING_FOR_VILLAGER_PROFESSION_CLEAR;
                } else if (!BlockUtils.breakBlock(rollingBlockPos, true)) {
                    error("无法破坏指定的方块");
                    toggle();
                }
            }
            case ROLLING_WAITING_FOR_VILLAGER_PROFESSION_CLEAR -> {
                if (mc.world.getBlockState(rollingBlockPos).isOf(Blocks.LECTERN)) {
                    if (cfDiscrepancy.get()) {
                        info("滚动方块挖掘被回滚？");
                    }
                    currentState = State.ROLLING_BREAKING_BLOCK;
                    return;
                }
                if (rollingVillager.getVillagerData().getProfession() == VillagerProfession.NONE) {
                    // info("职业已清除");
                    currentState = State.ROLLING_PLACING_BLOCK;
                }
            }
            case ROLLING_PLACING_BLOCK -> {
                FindItemResult item = InvUtils.findInHotbar(rollingBlock.asItem());
                if (!item.found()) {
                    placeFailed("快捷栏中未找到讲台");
                    return;
                }
                if (!BlockUtils.canPlace(rollingBlockPos, true)) {
                    placeFailed("无法放置讲台");
                    return;
                }
                if (!BlockUtils.place(rollingBlockPos, item, headRotateOnPlace.get(), 5)) {
                    placeFailed("放置讲台失败");
                    return;
                }
                currentState = State.ROLLING_WAITING_FOR_VILLAGER_PROFESSION_NEW;
                if (maxProfessionWaitTime.get() > 0) {
                    currentProfessionWaitTime = System.currentTimeMillis();
                }
            }
            case ROLLING_WAITING_FOR_VILLAGER_PROFESSION_NEW -> {
                if (maxProfessionWaitTime.get() > 0 && (currentProfessionWaitTime + maxProfessionWaitTime.get() <= System.currentTimeMillis())) {
                    if (cfProfessionTimeout.get()) {
                        info("村民未在指定时间内获得职业");
                    }
                    currentState = State.ROLLING_BREAKING_BLOCK;
                    return;
                }
                if (mc.world.getBlockState(rollingBlockPos) == Blocks.AIR.getDefaultState()) {
                    if (cfDiscrepancy.get()) {
                        info("讲台放置被服务器回滚（反作弊？）");
                    }
                    currentState = State.ROLLING_PLACING_BLOCK;
                    return;
                }
                if (!mc.world.getBlockState(rollingBlockPos).isOf(Blocks.LECTERN)) {
                    if (cfDiscrepancy.get()) {
                        info("放置了错误的方块？！");
                    }
                    currentState = State.ROLLING_BREAKING_BLOCK;
                    return;
                }
                if (rollingVillager.getVillagerData().getProfession() != VillagerProfession.NONE) {
                    currentState = State.ROLLING_WAITING_FOR_VILLAGER_TRADES;
                    triggerInteract();
                }
            }
            default -> {
                // 等待其他状态
            }
        }
    }

    public static class RollingEnchantment implements ISerializable<RollingEnchantment> {
        private Identifier enchantment;
        private int minLevel;
        private int maxCost;
        private boolean enabled;

        public RollingEnchantment(Identifier enchantment, int minLevel, int maxCost, boolean enabled) {
            this.enchantment = enchantment;
            this.minLevel = minLevel;
            this.maxCost = maxCost;
            this.enabled = enabled;
        }

        public RollingEnchantment() {
            enchantment = Identifier.of("minecraft", "protection");
            minLevel = 0;
            maxCost = 0;
            enabled = false;
        }

        @Override
        public NbtCompound toTag() {
            NbtCompound tag = new NbtCompound();
            tag.putString("enchantment", enchantment.toString());
            tag.putInt("minLevel", minLevel);
            tag.putInt("maxCost", maxCost);
            tag.putBoolean("enabled", enabled);
            return tag;
        }

        @Override
        public RollingEnchantment fromTag(NbtCompound tag) {
            enchantment = Identifier.tryParse(tag.getString("enchantment"));
            minLevel = tag.getInt("minLevel");
            maxCost = tag.getInt("maxCost");
            enabled = tag.getBoolean("enabled");
            return this;
        }
    }
}

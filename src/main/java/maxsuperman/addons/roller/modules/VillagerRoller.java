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
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.s2c.play.SetTradeOffersS2CPacket;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.EnchantmentTags;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
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

public class VillagerRoller extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSound = settings.createGroup("Sound");
    private final SettingGroup sgChatFeedback = settings.createGroup("Chat feedback", false);

    private final Setting<Boolean> disableIfFound = sgGeneral.add(new BoolSetting.Builder()
        .name("找到后禁用")
        .description("如果在列表中找到附魔，则将其禁用")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disconnectIfFound = sgGeneral.add(new BoolSetting.Builder()
        .name("找到后断开连接")
        .description("如果找到目标附魔，则自动断开服务器连接")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> saveListToConfig = sgGeneral.add(new BoolSetting.Builder()
        .name("保存列表到配置")
        .description("切换是否将滚动列表保存到配置文件和剪贴板")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enablePlaySound = sgGeneral.add(new BoolSetting.Builder()
        .name("启用声音提醒")
        .description("找到目标交易时播放声音")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<SoundEvent>> sound = sgSound.add(new SoundEventListSetting.Builder()
        .name("播放的声音")
        .description("启用后，找到目标交易时播放该声音")
        .defaultValue(Collections.singletonList(SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK))
        .build()
    );

    private final Setting<Double> soundPitch = sgSound.add(new DoubleSetting.Builder()
        .name("声音音高")
        .description("播放声音的音高（0~8）")
        .defaultValue(1.0)
        .min(0)
        .sliderRange(0, 8)
        .build()
    );

    private final Setting<Double> soundVolume = sgSound.add(new DoubleSetting.Builder()
        .name("声音音量")
        .description("播放声音的音量（0~1）")
        .defaultValue(1.0)
        .min(0)
        .sliderRange(0, 1)
        .build()
    );

    private final Setting<Boolean> pauseOnScreen = sgGeneral.add(new BoolSetting.Builder()
        .name("屏幕打开时暂停")
        .description("如果打开了任意界面，则暂停滚动")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> headRotateOnPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("放置方块时转向")
        .description("放置方块时是否看向它？")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> failedToPlaceDelay = sgGeneral.add(new IntSetting.Builder()
        .name("放置失败延迟")
        .description("方块放置失败后的延迟时间（毫秒）")
        .defaultValue(1500)
        .min(0)
        .sliderRange(0, 10000)
        .build()
    );

    private final Setting<Boolean> failedToPlaceDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("放置失败即停用")
        .description("如果方块放置失败，则禁用滚动器")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> maxProfessionWaitTime = sgGeneral.add(new IntSetting.Builder()
        .name("最大职业等待时间")
        .description("村民未获取职业时的最大等待时间（毫秒），0 表示不限制")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 10000)
        .build()
    );

    private final Setting<Boolean> onlyTradeable = sgGeneral.add(new BoolSetting.Builder()
        .name("仅显示可交易附魔")
        .description("隐藏标记为不可交易的附魔")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> sortEnchantments = sgGeneral.add(new BoolSetting.Builder()
        .name("按名称排序附魔")
        .description("按名称顺序显示附魔")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> instantRebreak = sgGeneral.add(new BoolSetting.Builder()
        .name("使用 CivBreak 瞬间破坏讲台")
        .description("使用 CivBreak 即时破坏讲台方块（建议站在讲台槽位上)")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> cfSetup = sgChatFeedback.add(new BoolSetting.Builder()
        .name("初始引导提示")
        .description("显示初始操作提示 (否则在模块列表状态中表示)")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfPausedOnScreen = sgChatFeedback.add(new BoolSetting.Builder()
        .name("界面打开时暂停")
        .description("滚动已暂停，请与村民交互以继续")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfLowerLevel = sgChatFeedback.add(new BoolSetting.Builder()
        .name("发现低等级附魔")
        .description("发现了附魔 %s 但不是最高等级：%d（最高）> %d（实际）")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfTooExpensive = sgChatFeedback.add(new BoolSetting.Builder()
        .name("价格过高")
        .description("发现了附魔 %s 但价格过高：%s（上限）< %d（实际）")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfIgnored = sgChatFeedback.add(new BoolSetting.Builder()
        .name("不在列表中的附魔")
        .description("发现了附魔 %s 但它不在你的目标列表中")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfProfessionTimeout = sgChatFeedback.add(new BoolSetting.Builder()
        .name("职业获取超时")
        .description("村民未能在指定时间内获得职业")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfPlaceFailed = sgChatFeedback.add(new BoolSetting.Builder()
        .name("放置失败提示")
        .description("放置失败，无法放置或热键栏中无讲台 (它们仍然会触发放置失败的设置)")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfDiscrepancy = sgChatFeedback.add(new BoolSetting.Builder()
        .name("状态异常提示")
        .description("滚动器进入了一个意外的状态（可能是反作弊干扰）")
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
        super(Categories.Misc, "村民交易滚动器", "滚动村民交易");
    }

    @Override
    public void onActivate() {
        if (toggleOnBindRelease) {
            toggleOnBindRelease = false;
            if (cfSetup.get()) {
                warning("您之前将“按下绑定后切换”设置为开启，我已经帮您关闭了，省去了您后续排错的麻烦");
            }
        }
        currentState = State.WAITING_FOR_TARGET_BLOCK;
        if (cfSetup.get()) {
            info("请攻击你想要滚动的方块");
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
            NbtList l = tag.getListOrEmpty("rolling");
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
            error("File does not exist or can not be loaded");
            return false;
        }
        NbtCompound r = null;
        try {
            r = NbtIo.read(f.toPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (r == null) {
            error("Failed to load nbt from file");
            return false;
        }
        NbtList l = r.getListOrEmpty("rolling");
        searchingEnchants.clear();
        for (NbtElement e : l) {
            if (e.getType() != NbtElement.COMPOUND_TYPE) {
                error("Invalid list element");
                return false;
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
            error("Failed to make directories");
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
        WSection loadDataSection = list.add(theme.section("Config Saving")).expandX().widget();

        WTable control = loadDataSection.add(theme.table()).expandX().widget();

        WTextBox savedConfigName = control.add(theme.textBox("default")).expandWidgetX().expandCellX().expandX().widget();
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
            if (!CONFIG_PATH.toFile().mkdirs()) error("Failed to create directory [{}]", CONFIG_PATH);
        } else {
            try (DirectoryStream<Path> configDir = Files.newDirectoryStream(CONFIG_PATH)) {
                for (Path config : configDir) {
                    configs.add(FilenameUtils.removeExtension(config.getFileName().toString()));
                }
            } catch (IOException e) {
                error("无法列出目录内容", e);
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
                    error("加载失败");
                }
            };
        }

        WSection enchantments = list.add(theme.section("Enchantments")).expandX().widget();

        WTable table = enchantments.add(theme.table()).expandX().widget();
        table.add(theme.item(Items.BOOK.getDefaultStack()));
        table.add(theme.label("Enchantment"));
        table.add(theme.label("Level"));
        table.add(theme.label("Cost"));
        table.add(theme.label("Enabled"));
        table.add(theme.label("Remove"));
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
            c.action = () -> mc.setScreen(new EnchantmentSelectScreen(theme, onlyTradeable.get(), sel -> {
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
            lev.tooltip = "Minimum enchantment level, 0 acts as maximum possible only (for custom 0 acts like 1)";

            WHorizontalList costbox = table.add(theme.horizontalList()).minWidth(50).expandX().widget();
            WIntEdit cost = costbox.add(theme.intEdit(e.maxCost, 0, 64, false)).minWidth(40).expandX().widget();
            cost.action = () -> e.maxCost = cost.get();
            cost.tooltip = "Maximum cost in emeralds, 0 means no limit";

            WButton setOptimal = costbox.add(theme.button("O")).widget();
            setOptimal.tooltip = "Set to optimal price (2 + maxLevel*3) (double if treasure) (if known)";
            setOptimal.action = () -> {
                list.clear();
                en.ifPresent(enchantmentReference -> e.maxCost = getMinimumPrice(enchantmentReference));
                fillWidget(theme, list);
            };

            WCheckbox enabled = table.add(theme.checkbox(e.enabled)).widget();
            enabled.action = () -> e.enabled = enabled.checked;
            enabled.tooltip = "Enabled?";

            WMinus del = table.add(theme.minus()).widget();
            del.action = () -> {
                list.clear();
                searchingEnchants.remove(e);
                fillWidget(theme, list);
            };
            table.row();
        }

        WTable controls = list.add(theme.table()).expandX().widget();

        WButton removeAll = controls.add(theme.button("移除全部")).expandX().widget();
        removeAll.action = () -> {
            list.clear();
            searchingEnchants.clear();
            fillWidget(theme, list);
        };

        WButton add = controls.add(theme.button("添加")).expandX().widget();
        add.action = () -> mc.setScreen(new EnchantmentSelectScreen(theme, onlyTradeable.get(), e -> {
            e.minLevel = 1;
            e.maxCost = 64;
            e.enabled = true;
            searchingEnchants.add(e);
            list.clear();
            fillWidget(theme, list);
        }));

        WButton addAll = controls.add(theme.button("全部添加")).expandX().widget();
        addAll.action = () -> {
            list.clear();
            searchingEnchants.clear();
            if (reg.isPresent()) {
                for (RegistryEntry<Enchantment> e : getEnchants(onlyTradeable.get())) {
                    searchingEnchants.add(new RollingEnchantment(reg.get().getId(e.value()), e.value().getMaxLevel(), getMinimumPrice(e), true));
                }
            }
            fillWidget(theme, list);
        };
        controls.row();

        WButton setOptimalForAll = controls.add(theme.button("全部设为最优价格")).expandX().widget();
        setOptimalForAll.action = () -> {
            list.clear();
            if (reg.isPresent()) {
                for (RollingEnchantment e : searchingEnchants) {
                    reg.get().getEntry(e.enchantment).ifPresent(enchantmentReference -> e.maxCost = getMinimumPrice(enchantmentReference));
                }
            }
            fillWidget(theme, list);
        };

        WButton priceBumpUp = controls.add(theme.button("全部 +1 价格")).expandX().widget();
        priceBumpUp.action = () -> {
            list.clear();
            for (RollingEnchantment e : searchingEnchants) {
                if (e.maxCost < 64) e.maxCost++;
            }
            fillWidget(theme, list);
        };

        WButton priceBumpDown = controls.add(theme.button("全部 -1 价格")).expandX().widget();
        priceBumpDown.action = () -> {
            list.clear();
            for (RollingEnchantment e : searchingEnchants) {
                if (e.maxCost > 0) e.maxCost--;
            }
            fillWidget(theme, list);
        };
        controls.row();

        WButton setZeroForAll = controls.add(theme.button("全部设为无上限价格")).expandX().widget();
        setZeroForAll.action = () -> {
            list.clear();
            for (RollingEnchantment e : searchingEnchants) {
                e.maxCost = 0;
            }
            fillWidget(theme, list);
        };

        WButton enableAll = controls.add(theme.button("全部启用")).expandX().widget();
        enableAll.action = () -> {
            list.clear();
            for (RollingEnchantment e : searchingEnchants) {
                e.enabled = true;
            }
            fillWidget(theme, list);
        };

        WButton disableAll = controls.add(theme.button("全部禁用")).expandX().widget();
        disableAll.action = () -> {
            list.clear();
            for (RollingEnchantment e : searchingEnchants) {
                e.enabled = false;
            }
            fillWidget(theme, list);
        };
        controls.row();

    }

    public List<RegistryEntry<Enchantment>> getEnchants(boolean onlyTradeable) {
        if (mc.world == null) {
            return Collections.emptyList();
        }
        var reg = mc.world.getRegistryManager().getOptional(RegistryKeys.ENCHANTMENT);
        if (reg.isEmpty()) {
            return Collections.emptyList();
        }
        List<RegistryEntry<Enchantment>> available = new ArrayList<>();
        if (onlyTradeable) {
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
                info("滚动已暂停，请与村民交互以继续");
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
                                info(String.format("发现附魔 %s 但未达到最高等级：%d（最高）> %d（当前）",
                                    enchantName, ml, enchantLevel));
                            }
                            continue;
                        }
                    } else if (e.minLevel > enchantLevel) {
                        if (cfLowerLevel.get()) {
                            info(String.format("发现附魔 %s，但等级过低：%d（请求等级）> %d（实际等级）",
                                enchantName, e.minLevel, enchantLevel));
                        }
                        continue;
                    }
                    if (e.maxCost > 0 && offer.getOriginalFirstBuyItem().getCount() > e.maxCost) {
                        if (cfTooExpensive.get()) {
                            info(String.format("现 %s 附魔，但其代价过高： %s（最高价格）< %d（附魔消耗）",
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
                        String levelText = (enchantLevel > 1 || enchant.key().value().getMaxLevel() > 1) ? " " + enchantLevel : "";
                        String message = String.format(
                            "%s[%s%s%s] Found enchant %s%s%s%s for %s%d%s emeralds and automatically disconnected.",
                            Formatting.GRAY,
                            Formatting.GREEN,
                            title,
                            Formatting.GRAY,
                            Formatting.WHITE,
                            enchantName,
                            levelText,
                            Formatting.GRAY,
                            Formatting.WHITE,
                            offer.getOriginalFirstBuyItem().getCount(),
                            Formatting.GRAY
                        );
                        mc.getNetworkHandler().getConnection().disconnect(Text.of(message));
                    }
                    break;
                }
                if (!found && cfIgnored.get()) {
                    info(String.format("发现了附魔 %s 但它不在你的列表中", enchantName));
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
            info("We got your villager");
        }
        event.cancel();
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onStartBreakingBlockEvent(StartBreakingBlockEvent event) {
        if (currentState != State.WAITING_FOR_TARGET_BLOCK) return;

        rollingBlockPos = event.blockPos;
        rollingBlock = mc.world.getBlockState(rollingBlockPos).getBlock();
        currentState = State.WAITING_FOR_TARGET_VILLAGER;
        if (instantRebreak.get()) {
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, rollingBlockPos, Direction.UP));
        }
        if (cfSetup.get()) {
            info("Rolling block selected, now interact with villager you want to roll");
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
                if (instantRebreak.get()) {
                    mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, rollingBlockPos, Direction.DOWN));
                }
                if (mc.world.getBlockState(rollingBlockPos) == Blocks.AIR.getDefaultState()) {
                    // info("Block is broken, waiting for villager to clean profession...");
                    currentState = State.ROLLING_WAITING_FOR_VILLAGER_PROFESSION_CLEAR;
                } else if (!instantRebreak.get() && !BlockUtils.breakBlock(rollingBlockPos, true)) {
                    error("Can not break specified block");
                    toggle();
                }
            }
            case ROLLING_WAITING_FOR_VILLAGER_PROFESSION_CLEAR -> {
                if (mc.world.getBlockState(rollingBlockPos).isOf(Blocks.LECTERN)) {
                    if (cfDiscrepancy.get()) {
                        info("Rolling block mining reverted?");
                    }
                    currentState = State.ROLLING_BREAKING_BLOCK;
                    return;
                }
                rollingVillager.getVillagerData().profession().getKey().ifPresent(profession -> {
                    if (profession == VillagerProfession.NONE) {
                        // info("Profession cleared");
                        currentState = State.ROLLING_PLACING_BLOCK;
                    }
                });
            }
            case ROLLING_PLACING_BLOCK -> {
                FindItemResult item = InvUtils.findInHotbar(rollingBlock.asItem());
                if (!item.found()) {
                    placeFailed("Lectern not found in hotbar");
                    return;
                }
                if (!BlockUtils.canPlace(rollingBlockPos, true)) {
                    placeFailed("Can't place lectern");
                    return;
                }
                if (!BlockUtils.place(rollingBlockPos, item, headRotateOnPlace.get(), 5)) {
                    placeFailed("Failed to place lectern");
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
                        info("村民未能在指定时间内获得职业");
                    }
                    currentState = State.ROLLING_BREAKING_BLOCK;
                    return;
                }
                if (mc.world.getBlockState(rollingBlockPos) == Blocks.AIR.getDefaultState()) {
                    if (cfDiscrepancy.get()) {
                        info("讲台放置被服务器回滚（AC?");
                    }
                    currentState = State.ROLLING_PLACING_BLOCK;
                    return;
                }
                if (!mc.world.getBlockState(rollingBlockPos).isOf(Blocks.LECTERN)) {
                    if (cfDiscrepancy.get()) {
                        info("放错了方块？！");
                    }
                    currentState = State.ROLLING_BREAKING_BLOCK;
                    return;
                }
                rollingVillager.getVillagerData().profession().getKey().ifPresent(profession -> {
                    if (profession != VillagerProfession.NONE) {
                        currentState = State.ROLLING_WAITING_FOR_VILLAGER_TRADES;
                        triggerInteract();
                    }
                });
            }
            default -> {
                // Wait for another state
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
            enchantment = Identifier.tryParse(tag.getString("enchantment", ""));
            minLevel = tag.getInt("minLevel", 1);
            maxCost = tag.getInt("maxCost", 64);
            enabled = tag.getBoolean("enabled", true);
            return this;
        }
    }
}

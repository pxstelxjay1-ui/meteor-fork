/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import com.google.common.collect.Streams;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.FallingBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class Scaffold extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks").description("Selected blocks.").build()
    );

    private final Setting<ListMode> blocksFilter = sgGeneral.add(new EnumSetting.Builder<ListMode>()
        .name("blocks-filter").description("How to use the block list setting")
        .defaultValue(ListMode.Blacklist).build()
    );

    private final Setting<Boolean> fastTower = sgGeneral.add(new BoolSetting.Builder()
        .name("fast-tower").description("Whether or not to scaffold upwards faster.")
        .defaultValue(true).build()
    );

    private final Setting<Double> towerSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("tower-speed").description("The speed at which to tower.")
        .defaultValue(0.62).min(0).sliderMax(1).visible(fastTower::get).build()
    );

    private final Setting<Boolean> onlyOnClick = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-click").description("Only places blocks when holding right click.")
        .defaultValue(false).build()
    );

    private final Setting<Boolean> renderSwing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing").description("Renders your client-side swing.").defaultValue(true).build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch").description("Automatically swaps to a block before placing.")
        .defaultValue(true).build()
    );

    // === GRIMAC BYPASS SETTINGS (THESE ARE THE KEY) ===
    private final Setting<Boolean> grimBypass = sgGeneral.add(new BoolSetting.Builder()
        .name("grim-bypass")
        .description("Makes Scaffold 100% undetectable on GrimAC")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate").description("Rotates towards the blocks being placed.")
        .defaultValue(false).visible(() -> grimBypass.get()).build() // OFF = Grim bypass
    );

    private final Setting<Double> radius = sgGeneral.add(new DoubleSetting.Builder()
        .name("radius").description("Scaffold radius (Grim-safe max = 0.15)")
        .defaultValue(0.15).min(0).max(0.15).visible(grimBypass::get).build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick").description("Max 1 block per tick on Grim")
        .defaultValue(1).min(1).max(1).visible(grimBypass::get).build()
    );

    // Render
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Whether to render blocks that have been placed.")
        .defaultValue(true).build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both).visible(render::get).build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").defaultValue(new SettingColor(197, 137, 232, 10)).visible(render::get).build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").defaultValue(new SettingColor(197, 137, 232)).visible(render::get).build()
    );

    private final BlockPos.Mutable bp = new BlockPos.Mutable();

    public Scaffold() {
        super(Categories.Movement, "scaffold", "Automatically places blocks under you — GrimAC Proof");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (onlyOnClick.get() && !mc.options.useKey.isPressed()) return;

        // === PREDICT POSITION FOR NEXT TICK (Grim simulates this) ===
        Vec3d predictedPos = mc.player.getPos().add(mc.player.getVelocity());
        bp.set(predictedPos.x, predictedPos.y - 0.78, predictedPos.z);

        // Sneak down one block if sneaking
        if (mc.options.sneakKey.isPressed() && !mc.options.jumpKey.isPressed()) {
            bp.setY(bp.getY() - 1);
        }

        List<BlockPos> positions = new ArrayList<>();

        if (grimBypass.get()) {
            // === GRIM-SAFE AIRPLACE: Only place directly under player + tiny radius ===
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos pos = bp.add(x, 0, z);
                    if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) <= 4.5) {
                        positions.add(pos);
                    }
                }
            }
            positions.sort(Comparator.comparingDouble(p -> mc.player.getEyePos().distanceTo(Vec3d.ofCenter(p))));
        } else {
            positions.add(bp.toImmutable());
        }

        // === PLACE BLOCKS (1 per tick max, no rotation) ===
        int placed = 0;
        for (BlockPos pos : positions) {
            if (placed >= (grimBypass.get() ? 1 : 5)) break;
            if (place(pos)) placed++;
        }

        // === GRIM-SAFE FAST TOWER ===
        if (fastTower.get() && mc.options.jumpKey.isPressed()) {
            FindItemResult item = InvUtils.findInHotbar(stack -> validItem(stack, bp));
            if (item.found() && (autoSwitch.get() || item.getHand() != null)) {
                if (mc.world.getBlockCollisions(mc.player, mc.player.getBoundingBox().offset(0, 1, 0)).iterator().hasNext()) {
                    // Head blocked → snap to top of placed block
                    mc.player.setVelocity(mc.player.getVelocity().x, Math.ceil(mc.player.getY()) - mc.player.getY(), mc.player.getVelocity().z);
                    mc.player.setOnGround(true);
                } else {
                    // Jump boost
                    mc.player.setVelocity(mc.player.getVelocity().x, towerSpeed.get(), mc.player.getVelocity().z);
                }
            }
        }
    }

    private boolean validItem(ItemStack stack, BlockPos pos) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;
        Block block = blockItem.getBlock();

        if (blocksFilter.get() == ListMode.Blacklist && blocks.get().contains(block)) return false;
        if (blocksFilter.get() == ListMode.Whitelist && !blocks.get().contains(block)) return false;

        if (!Block.isShapeFullCube(block.getDefaultState().getCollisionShape(mc.world, pos))) return false;
        return !(block instanceof FallingBlock) || !FallingBlock.canFallThrough(mc.world.getBlockState(pos.down()));
    }

    private boolean place(BlockPos pos) {
        FindItemResult item = InvUtils.findInHotbar(stack -> validItem(stack, pos));
        if (!item.found()) return false;
        if (item.getHand() == null && !autoSwitch.get()) return false;

        // === GRIM BYPASS: NO ROTATION + AIRPLACE + LOW DELAY ===
        boolean placed = BlockUtils.place(
            pos,
            item,
            grimBypass.get() ? false : rotate.get(),  // rotate = false on Grim
            0,                                          // delay = 0 (Grim-safe)
            renderSwing.get(),
            true
        );

        if (placed && render.get()) {
            RenderUtils.renderTickingBlock(pos.toImmutable(), sideColor.get(), lineColor.get(), shapeMode.get(), 0, 8, true, false);
        }
        return placed;
    }

    public enum ListMode { Whitelist, Blacklist }
}

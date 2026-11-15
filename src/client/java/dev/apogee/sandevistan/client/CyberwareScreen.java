package dev.apogee.sandevistan.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.apogee.sandevistan.ApogeeItems;
import dev.apogee.sandevistan.implant.CyberwareScreenHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/**
 * "Cyberware bay": dark sci-fi panel with one dedicated implant slot above the player inventory.
 * Everything is drawn with plain fills/gradients (no texture), so it works everywhere and stays
 * cheap on the GUI scale of any resolution.
 */
public class CyberwareScreen extends HandledScreen<CyberwareScreenHandler> {
    private static final int WIDTH = 176;
    private static final int HEIGHT = 176;

    private static final int GRID_X = 8;
    private static final int ROW_Y = 96;
    private static final int HOTBAR_Y = 150;
    private static final int CELL = 18;

    // neon palette
    private static final int NEON = 0x39FF88;
    private static final int LIME = 0xB6FF3C;
    private static final int PANEL_TOP = 0x0D1524;
    private static final int PANEL_BOTTOM = 0x05080F;
    private static final int PLATE_FRAME = 0x171F30;
    private static final int PLATE_INNER = 0x0E1420;
    private static final int PLATE_INNER_ACTIVE = 0x0C1B13;
    private static final int PLATE_FRAME_ACTIVE = 0x1D3327;
    private static final int TEXT_MAIN = 0xD8F7E4;
    private static final int TEXT_DIM = 0x6E7A90;
    private static final int GOOD = 0x8AFFC1;
    private static final int BAD = 0x7E8899;

    private static final ItemStack GHOST = new ItemStack(ApogeeItems.SANDEVISTAN);

    public CyberwareScreen(CyberwareScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = WIDTH;
        this.backgroundHeight = HEIGHT;
    }

    private boolean installed() {
        return this.handler.getSlot(0).hasStack();
    }

    // ------------------------------------------------------------------ background

    @Override
    protected void drawBackground(DrawContext g, float delta, int mouseX, int mouseY) {
        int left = (this.width - WIDTH) / 2;
        int top = (this.height - HEIGHT) / 2;
        boolean installed = installed();
        float pulse = 0.5f + 0.5f * MathHelper.sin(ApogeeFx.time() * 0.09f);

        // drop shadow, then the panel itself
        g.fill(left - 2, top - 2, left + WIDTH + 2, top + HEIGHT + 2, argb(0x000000, 0.5f));
        g.fillGradient(left, top, left + WIDTH, top + HEIGHT, PANEL_TOP, PANEL_BOTTOM);

        // neon frame
        float frameAlpha = 0.45f + 0.25f * pulse;
        g.fill(left, top, left + WIDTH, top + 1, argb(NEON, frameAlpha));
        g.fill(left, top + HEIGHT - 1, left + WIDTH, top + HEIGHT, argb(NEON, frameAlpha));
        g.fill(left, top, left + 1, top + HEIGHT, argb(NEON, frameAlpha));
        g.fill(left + WIDTH - 1, top, left + WIDTH, top + HEIGHT, argb(NEON, frameAlpha));
        corner(g, left, top, 1, 1);
        corner(g, left + WIDTH - 1, top, -1, 1);
        corner(g, left, top + HEIGHT - 1, 1, -1);
        corner(g, left + WIDTH - 1, top + HEIGHT - 1, -1, -1);

        // neon divider under the title
        divider(g, left, top + 19, pulse);

        // ------------------------------------------------ implant plate
        int slotX = left + CyberwareScreenHandler.IMPLANT_X;
        int slotY = top + CyberwareScreenHandler.IMPLANT_Y;
        int plateX = slotX - 4;
        int plateY = slotY - 4;
        if (installed) {
            g.fill(plateX - 2, plateY - 2, plateX + 26 + 2, plateY + 26 + 2, argb(NEON, 0.08f + 0.10f * pulse));
            g.fill(plateX, plateY, plateX + 26, plateY + 26, PLATE_FRAME_ACTIVE);
            g.fill(plateX + 1, plateY + 1, plateX + 25, plateY + 25, PLATE_INNER_ACTIVE);
            frame(g, plateX, plateY, 26, argb(NEON, 0.55f + 0.40f * pulse));
        } else {
            g.fill(plateX, plateY, plateX + 26, plateY + 26, PLATE_FRAME);
            g.fill(plateX + 1, plateY + 1, plateX + 25, plateY + 25, PLATE_INNER);
            frame(g, plateX, plateY, 26, argb(NEON, 0.30f + 0.15f * pulse));
            // ghost hint of the implant inside the empty slot
            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 0.16f);
            try {
                g.drawItem(GHOST, slotX, slotY);
            } finally {
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            }
        }
        implantCornerBrackets(g, plateX, plateY, 26, installed ? NEON : 0x556070);

        // ------------------------------------------------ inventory plates
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                plate(g, left + GRID_X + col * CELL, top + ROW_Y + row * CELL);
            }
        }
        g.fill(left + GRID_X, top + HOTBAR_Y - 3, left + WIDTH - GRID_X, top + HOTBAR_Y - 2, argb(NEON, 0.15f));
        for (int col = 0; col < 9; col++) {
            plate(g, left + GRID_X + col * CELL, top + HOTBAR_Y);
        }
    }

    /** 1px plate backgrounds behind the vanilla slots (vanilla draws nothing for custom panels). */
    private static void plate(DrawContext g, int x, int y) {
        g.fill(x - 1, y - 1, x + 17, y + 17, PLATE_FRAME);
        g.fill(x, y, x + 16, y + 16, PLATE_INNER);
    }

    private static void frame(DrawContext g, int x, int y, int size, int color) {
        g.fill(x, y, x + size, y + 1, color);
        g.fill(x, y + size - 1, x + size, y + size, color);
        g.fill(x, y, x + 1, y + size, color);
        g.fill(x + size - 1, y, x + size, y + size, color);
    }

    /** L-shaped corner brackets around the implant plate. */
    private static void implantCornerBrackets(DrawContext g, int x, int y, int size, int color) {
        int c = argb(color, 0.95f);
        int len = 6;
        // top-left
        g.fill(x - 2, y - 2, x - 2 + len, y - 1, c);
        g.fill(x - 2, y - 2, x - 1, y - 2 + len, c);
        // top-right
        g.fill(x + size + 2 - len, y - 2, x + size + 2, y - 1, c);
        g.fill(x + size + 1, y - 2, x + size + 2, y - 2 + len, c);
        // bottom-left
        g.fill(x - 2, y + size + 1, x - 2 + len, y + size + 2, c);
        g.fill(x - 2, y + size + 2 - len, x - 1, y + size + 2, c);
        // bottom-right
        g.fill(x + size + 2 - len, y + size + 1, x + size + 2, y + size + 2, c);
        g.fill(x + size + 1, y + size + 2 - len, x + size + 2, y + size + 2, c);
    }

    /** Panel corner brackets for the whole window. */
    private static void corner(DrawContext g, int x, int y, int dx, int dy) {
        int c = argb(NEON, 0.9f);
        int len = 10;
        int x0 = dx > 0 ? x : x - (len - 1);
        int y0 = dy > 0 ? y : y - 1;
        g.fill(x0, y0, x0 + len, y0 + 1, c);
        int vx = dx > 0 ? x : x - 1;
        int vy = dy > 0 ? y : y - (len - 1);
        g.fill(vx, vy, vx + 1, vy + len, c);
    }

    /** Horizontal neon divider that fades out towards both edges. */
    private static void divider(DrawContext g, int left, int y, float pulse) {
        int center = left + WIDTH / 2;
        int half = WIDTH / 2 - 10;
        for (int i = 0; i < half; i++) {
            float k = 1.0f - (float) i / half;
            int alpha = argb(NEON, 0.55f * k * k);
            g.fill(center - 10 - i, y, center - 10 - i + 1, y + 1, alpha);
            g.fill(center + 10 + i, y, center + 10 + i + 1, y + 1, alpha);
        }
        g.fill(center - 10, y - 1, center + 10, y + 2, argb(NEON, 0.65f + 0.30f * pulse));
        g.fill(center - 2, y - 2, center + 2, y + 3, argb(LIME, 0.85f));
    }

    // ------------------------------------------------------------------ foreground (text)

    @Override
    protected void drawForeground(DrawContext g, int mouseX, int mouseY) {
        int left = (this.width - WIDTH) / 2;
        int top = (this.height - HEIGHT) / 2;
        boolean installed = installed();

        Text title = Text.translatable("gui.sandevistan_apogee.title");
        g.drawText(this.textRenderer, title, left + (WIDTH - this.textRenderer.getWidth(title)) / 2,
                top + 7, TEXT_MAIN, true);

        String tag = "SANDEVISTAN";
        int tagWidth = this.textRenderer.getWidth(tag);
        g.drawText(this.textRenderer, tag, left + (WIDTH - tagWidth) / 2, top + 55,
                installed ? GOOD : BAD, true);

        Text status = Text.translatable(installed ? "gui.sandevistan_apogee.installed" : "gui.sandevistan_apogee.empty");
        int statusWidth = this.textRenderer.getWidth(status);
        g.drawText(this.textRenderer, status, left + (WIDTH - statusWidth) / 2, top + 66,
                installed ? GOOD : BAD, true);

        g.drawText(this.textRenderer, Text.translatable("gui.sandevistan_apogee.inventory"),
                left + GRID_X, top + 85, TEXT_DIM, true);
    }

    // ------------------------------------------------------------------ helpers

    static int argb(int rgb, float alpha) {
        int a = MathHelper.clamp(Math.round(alpha * 255.0f), 0, 255);
        return (a << 24) | (rgb & 0xFFFFFF);
    }
}

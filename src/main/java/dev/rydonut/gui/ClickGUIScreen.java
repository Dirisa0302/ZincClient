package dev.rydonut.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.lwjgl.glfw.GLFW;

import dev.rydonut.config.ConfigManager;
import dev.rydonut.core.Category;
import dev.rydonut.core.Module;
import dev.rydonut.core.ModuleManager; // TODO: sua cho dung class quan ly module cua ban
import dev.rydonut.settings.DoubleSetting;
import dev.rydonut.settings.EnumSetting;
import dev.rydonut.settings.KeybindSetting;
import dev.rydonut.settings.Setting;
import dev.rydonut.settings.StringListSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Fluent-style GUI (giong Fluent UI library cua Roblox):
 * cua so toi mau bo goc, sidebar tab ben trai co indicator truot, noi dung cuon
 * ben phai gom Section / Toggle / Slider / Dropdown / Keybind / Button, kem
 * thong bao (toast) goc phai duoi. Keo tieu de de di chuyen cua so.
 * ESC hoac Right Shift de dong (se luu profile).
 *
 * Luu y: sua nextProfileName() trong ClickGUIScreen thanh package-private
 * (bo "private") de tab Settings goi duoc.
 */
public class FluentScreen extends Screen {
    private static final int WIN_W = 480;
    private static final int WIN_H = 320;
    private static final int TITLE_H = 30;
    private static final int SIDEBAR_W = 116;
    private static final int PAD = 8;
    private static final int GAP = 4;
    private static final int TAB_H = 22;

    // palette (ARGB)
    private static final int BG = 0xF0161618;
    private static final int SIDEBAR = 0xF01B1B1F;
    private static final int OUTLINE = 0xFF34343A;
    private static final int CARD = 0xFF222226;
    private static final int CARD_HOVER = 0xFF2B2B30;
    private static final int SELECT = 0xFF2C2C32;
    private static final int TEXT = 0xFFEDEDF0;
    private static final int TEXT_DIM = 0xFF8E8E98;
    private static final int ACCENT = 0xFF4C9EFF;
    private static final int OFF_TRACK = 0xFF3A3A41;
    private static final int SUCCESS = 0xFF5CD68A;

    /** 0..1, static de mo lai khong bi giat. */
    private static float openAnim;
    private static final List<Toast> TOASTS = new ArrayList<>();

    private final List<Tab> tabs = new ArrayList<>();
    private int selected;
    private float indicatorY = -1.0F;

    private int wx, wy, ww, wh;
    private boolean placed;
    private boolean draggingWindow;
    private double dragOffX, dragOffY;
    private boolean closing;

    private float dt = 0.016F;
    private long lastNanos;

    private float scroll, scrollTarget;
    private int contentHeight;
    private int cx, cy, cw, ch; // content rect (cap nhat moi frame)

    private Element active;

    public FluentScreen() {
        super(Component.literal("Rydonut"));
        buildTabs();
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new FluentScreen());
    }

    /** Hien thong bao goc phai duoi (goi o dau cung duoc, se hien khi GUI mo). */
    public static void notify(String text) {
        TOASTS.add(new Toast(text, System.currentTimeMillis()));
    }

    // ------------------------------------------------------------ noi dung

    /** Tu sinh moi tab tu Category, moi module la mot card co the mo rong de chinh setting. */
    private void buildTabs() {
        for (Category category : Category.values()) {
            List<Module> modules = modulesOf(category);
            if (modules.isEmpty()) {
                continue;
            }
            String raw = category.name();
            Tab t = tab(raw.charAt(0) + raw.substring(1).toLowerCase());
            for (Module m : modules) {
                t.elements.add(new ModuleCard(m));
            }
        }

        Tab settings = tab("Settings");
        settings.section("Profile");
        settings.button("Save", () -> {
            ConfigManager.saveProfile(ConfigManager.activeProfile());
            notify("Saved profile");
        });
        settings.button("Load", () -> {
            ConfigManager.loadProfile(ConfigManager.activeProfile());
            notify("Loaded profile");
        });
        settings.button("Export", () -> {
            ConfigManager.exportProfile(ConfigManager.activeProfile());
            notify("Exported profile");
        });
        settings.button("Import", () -> {
            ConfigManager.importLatestExport(ConfigManager.activeProfile());
            notify("Imported profile");
        });
        settings.button("New Profile", () -> {
            String name = ClickGUIScreen.nextProfileName();
            ConfigManager.newProfile(name);
            notify("New profile: " + name);
        });
        settings.button("HUD Editor", () -> Minecraft.getInstance().setScreen(new HudEditorScreen()));
    }

    /** TODO: thay bang cach ban lay module theo category (giong Panel cu). */
    private static List<Module> modulesOf(Category category) {
        return ModuleManager.getModules(category);
    }

    private Tab tab(String name) {
        Tab t = new Tab(name);
        tabs.add(t);
        return t;
    }

    // ------------------------------------------------------------ helpers ve

    private float approach(float cur, float target, float speed) {
        return cur + (target - cur) * (1.0F - (float) Math.exp(-speed * dt));
    }

    /** Nhan alpha voi hieu ung mo cua so. */
    private int c(int argb) {
        int a = (int) (((argb >>> 24) & 0xFF) * Mth.clamp(openAnim, 0.0F, 1.0F));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private static int lerpColor(int a, int b, float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        int ca = (int) (((a >>> 24) & 0xFF) + (((b >>> 24) & 0xFF) - ((a >>> 24) & 0xFF)) * t);
        int cr = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int cg = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int cb = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (ca << 24) | (cr << 16) | (cg << 8) | cb;
    }

    /** Hinh chu nhat bo goc (khong dung texture). Yeu cau h >= 2r. */
    private static void fillRounded(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(r * r - dy * dy));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
        }
        g.fill(x, y + r, x + w, y + h - r, color);
    }

    private void text(GuiGraphics g, Font f, String s, int x, int y, int color) {
        g.drawString(f, s, x, y, c(color));
    }

    /** MY_MODE -> "My mode". */
    private static String prettyEnum(String name) {
        String n = name.toLowerCase().replace('_', ' ');
        return n.isEmpty() ? n : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private static String keyName(int key) {
        if (key <= 0) {
            return "None";
        }
        return switch (key) {
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "LShift";
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RShift";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCtrl";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCtrl";
            case GLFW.GLFW_KEY_LEFT_ALT -> "LAlt";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "RAlt";
            case GLFW.GLFW_KEY_SPACE -> "Space";
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_ENTER -> "Enter";
            default -> {
                String n = GLFW.glfwGetKeyName(key, 0);
                yield n != null ? n.toUpperCase() : "Key " + key;
            }
        };
    }

    // ------------------------------------------------------------ render

    @Override
    public void init() {
        super.init();
        placed = false;
        lastNanos = 0L;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        dt = lastNanos == 0L ? 0.016F : Math.min(0.1F, (now - lastNanos) / 1.0E9F);
        lastNanos = now;

        openAnim = approach(openAnim, closing ? 0.0F : 1.0F, 14.0F);
        if (closing && openAnim < 0.02F) {
            openAnim = 0.0F;
            onClose();
            return;
        }
        renderBackground(g, mouseX, mouseY, delta);
        if (openAnim < 0.03F) {
            return; // tranh alpha ~0 bi coi la opaque khi ve chu
        }

        Font font = Minecraft.getInstance().font;
        ww = Math.min(WIN_W, g.guiWidth() - 20);
        wh = Math.min(WIN_H, g.guiHeight() - 20);
        if (!placed) {
            wx = (g.guiWidth() - ww) / 2;
            wy = (g.guiHeight() - wh) / 2;
            placed = true;
        }
        wx = Mth.clamp(wx, 0, Math.max(0, g.guiWidth() - ww));
        wy = Mth.clamp(wy, 0, Math.max(0, g.guiHeight() - wh));

        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(0.0F, (1.0F - openAnim) * 10.0F);

        // khung cua so
        fillRounded(g, wx - 1, wy - 1, ww + 2, wh + 2, 9, c(OUTLINE));
        fillRounded(g, wx, wy, ww, wh, 8, c(BG));

        renderSidebar(g, font, mouseX, mouseY);
        renderContent(g, font, mouseX, mouseY);

        pose.popMatrix();
        renderToasts(g, font);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        int alpha = (int) (0x70 * openAnim);
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), (alpha << 24) | 0x000000);
    }

    private void renderSidebar(GuiGraphics g, Font font, int mouseX, int mouseY) {
        fillRounded(g, wx, wy, SIDEBAR_W, wh, 8, c(SIDEBAR));
        g.fill(wx + SIDEBAR_W - 8, wy, wx + SIDEBAR_W, wy + wh, c(SIDEBAR)); // vuong goc ben phai
        g.fill(wx + SIDEBAR_W, wy, wx + SIDEBAR_W + 1, wy + wh, c(OUTLINE));

        // tieu de
        text(g, font, "Rydonut", wx + 12, wy + 11, TEXT);
        String ver = "v1.0";
        text(g, font, ver, wx + SIDEBAR_W - 10 - font.width(ver), wy + 11, TEXT_DIM);
        g.fill(wx + 8, wy + TITLE_H - 1, wx + SIDEBAR_W - 8, wy + TITLE_H, c(OUTLINE));

        // indicator truot
        int top = wy + TITLE_H + 6;
        float targetY = top + selected * (TAB_H + 2);
        indicatorY = indicatorY < 0 ? targetY : approach(indicatorY, targetY, 18.0F);
        fillRounded(g, wx + 6, Math.round(indicatorY), SIDEBAR_W - 12, TAB_H, 5, c(SELECT));
        fillRounded(g, wx + 6, Math.round(indicatorY) + 5, 2, TAB_H - 10, 1, c(ACCENT));

        for (int i = 0; i < tabs.size(); i++) {
            int ty = top + i * (TAB_H + 2);
            boolean hover = mouseX >= wx + 6 && mouseX < wx + SIDEBAR_W - 6 && mouseY >= ty && mouseY < ty + TAB_H;
            int color = i == selected ? TEXT : (hover ? 0xFFC4C4CC : TEXT_DIM);
            text(g, font, tabs.get(i).name, wx + 16, ty + 7, color);
        }

        String prof = ConfigManager.activeProfile();
        String line = "Profile: " + prof;
        while (font.width(line) > SIDEBAR_W - 20 && line.length() > 4) {
            line = line.substring(0, line.length() - 2);
        }
        text(g, font, line, wx + 12, wy + wh - 16, TEXT_DIM);
    }

    private void renderContent(GuiGraphics g, Font font, int mouseX, int mouseY) {
        Tab tab = tabs.get(selected);
        int baseX = wx + SIDEBAR_W + 1 + PAD;
        text(g, font, tab.name, baseX, wy + 11, TEXT);
        g.fill(baseX, wy + TITLE_H - 1, wx + ww - PAD, wy + TITLE_H, c(OUTLINE));

        cx = baseX;
        cy = wy + TITLE_H + PAD;
        cw = wx + ww - PAD - cx - 6; // chua cho cho scrollbar
        ch = wy + wh - PAD - cy;

        boolean inContent = mouseX >= cx && mouseX < cx + cw && mouseY >= cy && mouseY < cy + ch;

        int maxScroll = Math.max(0, contentHeight - ch);
        scrollTarget = Mth.clamp(scrollTarget, 0.0F, maxScroll);
        scroll = approach(scroll, scrollTarget, 16.0F);

        g.enableScissor(cx - 2, cy, cx + cw + 2, cy + ch);
        int y = cy - Math.round(scroll);
        int total = 0;
        for (Element e : tab.elements) {
            int h = e.height();
            e.x = cx;
            e.y = y;
            e.w = cw;
            e.render(g, font, mouseX, mouseY, inContent);
            y += h + GAP;
            total += h + GAP;
        }
        g.disableScissor();
        contentHeight = Math.max(0, total - GAP);

        if (maxScroll > 0) {
            int barH = Math.max(16, (int) ((float) ch * ch / contentHeight));
            int barY = cy + (int) ((ch - barH) * (scroll / maxScroll));
            fillRounded(g, cx + cw + 3, barY, 3, barH, 1, c(0xFF55555E));
        }
    }

    private void renderToasts(GuiGraphics g, Font font) {
        long now = System.currentTimeMillis();
        TOASTS.removeIf(t -> now - t.created > 2800L);
        int y = g.guiHeight() - 12;
        for (int i = TOASTS.size() - 1; i >= 0; i--) {
            Toast t = TOASTS.get(i);
            long age = now - t.created;
            float in = Mth.clamp(age / 220.0F, 0.0F, 1.0F);
            float out = Mth.clamp((2800L - age) / 300.0F, 0.0F, 1.0F);
            float k = Math.min(in, out);
            int w = font.width(t.text) + 26;
            int h = 22;
            int x = g.guiWidth() - 12 - w + (int) ((1.0F - in) * (w + 12));
            y -= h;
            int a = (int) (255 * k);
            fillRounded(g, x - 1, y - 1, w + 2, h + 2, 6, (a << 24) | 0x34343A);
            fillRounded(g, x, y, w, h, 5, (a << 24) | 0x1E1E22);
            fillRounded(g, x + 5, y + 5, 2, h - 10, 1, (a << 24) | (ACCENT & 0xFFFFFF));
            if (a > 8) {
                g.drawString(font, t.text, x + 14, y + 7, (a << 24) | 0xEDEDF0);
            }
            y -= 6;
        }
    }

    // ------------------------------------------------------------ input

    private void startClose() {
        closing = true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (closing) {
            return true;
        }
        int key = event.key();
        for (Element e : tabs.get(selected).elements) {
            if (e.key(key)) {
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            startClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (closing) {
            return true;
        }
        for (Element e : tabs.get(selected).elements) {
            if (e.typed(event.codepoint())) {
                return true;
            }
        }
        return super.charTyped(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (closing) {
            return true;
        }
        double mx = event.x();
        double my = event.y();
        int button = event.button();

        if (mx < wx || mx >= wx + ww || my < wy || my >= wy + wh) {
            return super.mouseClicked(event, doubled);
        }

        // keo cua so bang thanh tieu de
        if (button == 0 && my < wy + TITLE_H) {
            draggingWindow = true;
            dragOffX = mx - wx;
            dragOffY = my - wy;
            return true;
        }

        // sidebar tabs
        if (mx < wx + SIDEBAR_W) {
            int top = wy + TITLE_H + 6;
            for (int i = 0; i < tabs.size(); i++) {
                int ty = top + i * (TAB_H + 2);
                if (button == 0 && my >= ty && my < ty + TAB_H) {
                    if (i != selected) {
                        selected = i;
                        scroll = 0.0F;
                        scrollTarget = 0.0F;
                        contentHeight = 0;
                    }
                    return true;
                }
            }
            return true;
        }

        // noi dung
        if (mx >= cx && mx < cx + cw && my >= cy && my < cy + ch) {
            Tab tab = tabs.get(selected);
            for (Element e : tab.elements) {
                e.cancel();
            }
            for (Element e : tab.elements) {
                if (e.hit(mx, my) && e.click(mx, my, button)) {
                    active = e;
                    return true;
                }
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (closing) {
            return true;
        }
        if (draggingWindow) {
            wx = (int) (event.x() - dragOffX);
            wy = (int) (event.y() - dragOffY);
            return true;
        }
        if (active != null) {
            active.drag(event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingWindow = false;
        if (active != null) {
            active.release();
            active = null;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (closing) {
            return true;
        }
        if (mouseX >= cx && mouseX < cx + cw + 6 && mouseY >= cy && mouseY < cy + ch) {
            int maxScroll = Math.max(0, contentHeight - ch);
            scrollTarget = Mth.clamp(scrollTarget - (float) scrollY * 28.0F, 0.0F, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        ConfigManager.saveProfile(ConfigManager.activeProfile());
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------ model

    private record Toast(String text, long created) {
    }

    private final class Tab {
        final String name;
        final List<Element> elements = new ArrayList<>();

        Tab(String name) {
            this.name = name;
        }

        Tab section(String title) {
            elements.add(new SectionLabel(title));
            return this;
        }

        Tab toggle(String label, BooleanSupplier get, Consumer<Boolean> set) {
            elements.add(new Toggle(label, get, set));
            return this;
        }

        Tab slider(String label, double min, double max, double step, DoubleSupplier get, DoubleConsumer set) {
            elements.add(new Slider(label, min, max, step, get, set));
            return this;
        }

        Tab dropdown(String label, List<String> options, Supplier<String> get, Consumer<String> set) {
            elements.add(new Dropdown(label, options, get, set));
            return this;
        }

        Tab keybind(String label, IntSupplier get, IntConsumer set) {
            elements.add(new Keybind(label, get, set));
            return this;
        }

        Tab button(String label, Runnable action) {
            elements.add(new Button(label, action));
            return this;
        }
    }

    private abstract class Element {
        int x, y, w;

        abstract int height();

        abstract void render(GuiGraphics g, Font f, int mx, int my, boolean inContent);

        boolean click(double mx, double my, int button) {
            return false;
        }

        void drag(double mx) {
        }

        void release() {
        }

        void cancel() {
        }

        boolean key(int key) {
            return false;
        }

        boolean typed(int codepoint) {
            return false;
        }

        boolean hit(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + height();
        }

        boolean hovered(int mx, int my, boolean inContent) {
            return inContent && hit(mx, my);
        }
    }

    private final class SectionLabel extends Element {
        private final String title;

        SectionLabel(String title) {
            this.title = title;
        }

        @Override
        int height() {
            return 16;
        }

        @Override
        void render(GuiGraphics g, Font f, int mx, int my, boolean inContent) {
            text(g, f, title, x + 2, y + 4, TEXT_DIM);
            int lx = x + 6 + f.width(title);
            g.fill(lx, y + 8, x + w, y + 9, c(OUTLINE));
        }
    }

    private final class Toggle extends Element {
        private final String label;
        private final BooleanSupplier get;
        private final Consumer<Boolean> set;
        private float anim;
        private boolean init;

        Toggle(String label, BooleanSupplier get, Consumer<Boolean> set) {
            this.label = label;
            this.get = get;
            this.set = set;
        }

        @Override
        int height() {
            return 24;
        }

        @Override
        void render(GuiGraphics g, Font f, int mx, int my, boolean inContent) {
            boolean on = get.getAsBoolean();
            if (!init) {
                anim = on ? 1.0F : 0.0F;
                init = true;
            }
            anim = approach(anim, on ? 1.0F : 0.0F, 20.0F);
            fillRounded(g, x, y, w, 24, 5, c(hovered(mx, my, inContent) ? CARD_HOVER : CARD));
            text(g, f, label, x + 10, y + 8, TEXT);

            int sx = x + w - 40;
            int sy = y + 6;
            fillRounded(g, sx, sy, 30, 12, 6, c(lerpColor(OFF_TRACK, ACCENT, anim)));
            int kx = sx + 2 + Math.round(anim * 18.0F);
            fillRounded(g, kx, sy + 2, 8, 8, 4, c(0xFFFFFFFF));
        }

        @Override
        boolean click(double mx, double my, int button) {
            if (button != 0) {
                return false;
            }
            set.accept(!get.getAsBoolean());
            return false; // khong can giu "active"
        }
    }

    private final class Slider extends Element {
        private final String label;
        private final double min, max, step;
        private final DoubleSupplier get;
        private final DoubleConsumer set;
        private float anim;
        private boolean init;

        Slider(String label, double min, double max, double step, DoubleSupplier get, DoubleConsumer set) {
            this.label = label;
            this.min = min;
            this.max = max;
            this.step = step;
            this.get = get;
            this.set = set;
        }

        @Override
        int height() {
            return 34;
        }

        private void update(double mx) {
            double t = Mth.clamp((mx - (x + 10)) / (double) (w - 20), 0.0, 1.0);
            double v = min + t * (max - min);
            if (step > 0.0) {
                v = Math.round(v / step) * step;
            }
            set.accept(Mth.clamp(v, min, max));
        }

        @Override
        void render(GuiGraphics g, Font f, int mx, int my, boolean inContent) {
            double v = get.getAsDouble();
            float t = (float) Mth.clamp((v - min) / (max - min), 0.0, 1.0);
            if (!init) {
                anim = t;
                init = true;
            }
            anim = approach(anim, t, 24.0F);

            fillRounded(g, x, y, w, 34, 5, c(hovered(mx, my, inContent) ? CARD_HOVER : CARD));
            text(g, f, label, x + 10, y + 6, TEXT);
            String val = step % 1.0 == 0.0 ? String.valueOf((int) v) : String.format("%.2f", v);
            text(g, f, val, x + w - 10 - f.width(val), y + 6, TEXT_DIM);

            int tx = x + 10;
            int tw = w - 20;
            int ty = y + 23;
            fillRounded(g, tx, ty, tw, 4, 2, c(OFF_TRACK));
            int fw = Math.max(4, Math.round(tw * anim));
            fillRounded(g, tx, ty, fw, 4, 2, c(ACCENT));
            fillRounded(g, tx + Math.round((tw - 8) * anim), ty - 2, 8, 8, 4, c(0xFFFFFFFF));
        }

        @Override
        boolean click(double mx, double my, int button) {
            if (button != 0) {
                return false;
            }
            update(mx);
            return true;
        }

        @Override
        void drag(double mx) {
            update(mx);
        }
    }

    private final class Dropdown extends Element {
        private static final int ROW = 18;
        private final String label;
        private final List<String> options;
        private final Supplier<String> get;
        private final Consumer<String> set;
        private boolean expanded;

        Dropdown(String label, List<String> options, Supplier<String> get, Consumer<String> set) {
            this.label = label;
            this.options = options;
            this.get = get;
            this.set = set;
        }

        @Override
        int height() {
            return expanded ? 24 + options.size() * ROW + 4 : 24;
        }

        @Override
        void render(GuiGraphics g, Font f, int mx, int my, boolean inContent) {
            fillRounded(g, x, y, w, height(), 5, c(hovered(mx, my, inContent) && my < y + 24 ? CARD_HOVER : CARD));
            text(g, f, label, x + 10, y + 8, TEXT);
            String cur = get.get();
            String arrow = expanded ? "^" : "v";
            text(g, f, arrow, x + w - 10 - f.width(arrow), y + 8, TEXT_DIM);
            text(g, f, cur, x + w - 22 - f.width(cur), y + 8, ACCENT);

            if (expanded) {
                g.fill(x + 8, y + 24, x + w - 8, y + 25, c(OUTLINE));
                for (int i = 0; i < options.size(); i++) {
                    int ry = y + 27 + i * ROW;
                    boolean hov = inContent && mx >= x + 4 && mx < x + w - 4 && my >= ry && my < ry + ROW;
                    if (hov) {
                        fillRounded(g, x + 4, ry, w - 8, ROW, 4, c(SELECT));
                    }
                    String opt = options.get(i);
                    text(g, f, opt, x + 12, ry + 5, opt.equals(cur) ? ACCENT : TEXT);
                }
            }
        }

        @Override
        boolean click(double mx, double my, int button) {
            if (button != 0) {
                return false;
            }
            if (my < y + 24) {
                expanded = !expanded;
            } else {
                int idx = (int) ((my - (y + 27)) / ROW);
                if (idx >= 0 && idx < options.size()) {
                    set.accept(options.get(idx));
                }
                expanded = false;
            }
            return false;
        }
    }

    private final class Keybind extends Element {
        private final String label;
        private final IntSupplier get;
        private final IntConsumer set;
        private boolean listening;

        Keybind(String label, IntSupplier get, IntConsumer set) {
            this.label = label;
            this.get = get;
            this.set = set;
        }

        @Override
        int height() {
            return 24;
        }

        @Override
        void render(GuiGraphics g, Font f, int mx, int my, boolean inContent) {
            fillRounded(g, x, y, w, 24, 5, c(hovered(mx, my, inContent) ? CARD_HOVER : CARD));
            text(g, f, label, x + 10, y + 8, TEXT);
            String name = listening ? "..." : keyName(get.getAsInt());
            int bw = Math.max(34, f.width(name) + 14);
            int bx = x + w - 8 - bw;
            fillRounded(g, bx - 1, y + 3, bw + 2, 18, 5, c(listening ? ACCENT : OUTLINE));
            fillRounded(g, bx, y + 4, bw, 16, 4, c(0xFF1A1A1E));
            text(g, f, name, bx + (bw - f.width(name)) / 2, y + 8, listening ? ACCENT : TEXT_DIM);
        }

        @Override
        boolean click(double mx, double my, int button) {
            if (button == 0) {
                listening = true;
            }
            return false;
        }

        @Override
        void cancel() {
            listening = false;
        }

        @Override
        boolean key(int key) {
            if (!listening) {
                return false;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                // huy
            } else if (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) {
                set.accept(-1);
            } else {
                set.accept(key);
            }
            listening = false;
            return true;
        }
    }

    private final class Button extends Element {
        private final String label;
        private final Runnable action;
        private float flash;

        Button(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }

        @Override
        int height() {
            return 24;
        }

        @Override
        void render(GuiGraphics g, Font f, int mx, int my, boolean inContent) {
            flash = approach(flash, 0.0F, 8.0F);
            int base = hovered(mx, my, inContent) ? CARD_HOVER : CARD;
            fillRounded(g, x, y, w, 24, 5, c(lerpColor(base, ACCENT, flash)));
            text(g, f, label, x + (w - f.width(label)) / 2, y + 8, TEXT);
        }

        @Override
        boolean click(double mx, double my, int button) {
            if (button != 0) {
                return false;
            }
            flash = 1.0F;
            action.run();
            return false;
        }
    }

    /** Card cua mot Module: header (ten + cong tac), bam vao ten de mo rong danh sach setting. */
    private final class ModuleCard extends Element {
        private static final int HEADER = 26;
        private final Module module;
        private final List<Setting<?>> sources = new ArrayList<>();
        private final List<Element> children = new ArrayList<>();
        private boolean expanded;
        private float anim;
        private boolean init;
        private Element activeChild;

        @SuppressWarnings("unchecked")
        ModuleCard(Module module) {
            this.module = module;
            for (Setting<?> s : module.getSettings()) {
                Element e;
                if (s instanceof KeybindSetting) {
                    Setting<Integer> ks = (Setting<Integer>) s;
                    e = new Keybind(s.getName(), () -> ks.get(), v -> ks.set(Math.max(0, v)));
                } else if (s instanceof DoubleSetting ds) {
                    e = new Slider(s.getName(), ds.getMin(), ds.getMax(), ds.getStep(), () -> ds.get(), v -> ds.set(v));
                } else if (s instanceof EnumSetting<?> es) {
                    e = enumDropdown(es);
                } else if (s instanceof StringListSetting ls) {
                    e = new ListEditor(ls);
                } else if (s.get() instanceof Boolean) {
                    Setting<Boolean> bs = (Setting<Boolean>) s;
                    e = new Toggle(s.getName(), () -> bs.get(), bs::set);
                } else {
                    e = new InfoRow(s);
                }
                sources.add(s);
                children.add(e);
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Element enumDropdown(EnumSetting<?> es) {
            EnumSetting raw = es;
            Enum[] values = (Enum[]) es.values();
            List<String> names = new ArrayList<>();
            for (Enum v : values) {
                names.add(prettyEnum(v.name()));
            }
            return new Dropdown(es.getName(), names,
                    () -> prettyEnum(((Enum) raw.get()).name()),
                    v -> {
                        int i = names.indexOf(v);
                        if (i >= 0) {
                            raw.set(values[i]);
                        }
                    });
        }

        private List<Element> visible() {
            List<Element> out = new ArrayList<>();
            for (int i = 0; i < children.size(); i++) {
                if (sources.get(i).isVisible()) {
                    out.add(children.get(i));
                }
            }
            return out;
        }

        @Override
        int height() {
            if (!expanded) {
                return HEADER;
            }
            int h = HEADER + 6;
            for (Element e : visible()) {
                h += e.height() + GAP;
            }
            return h + 2;
        }

        @Override
        void render(GuiGraphics g, Font f, int mx, int my, boolean inContent) {
            boolean on = module.isEnabled();
            if (!init) {
                anim = on ? 1.0F : 0.0F;
                init = true;
            }
            anim = approach(anim, on ? 1.0F : 0.0F, 20.0F);

            boolean hoverHeader = hovered(mx, my, inContent) && my < y + HEADER;
            fillRounded(g, x, y, w, height(), 5, c(hoverHeader ? CARD_HOVER : CARD));
            text(g, f, module.getName(), x + 10, y + 9, on ? TEXT : 0xFFB4B4BC);
            String arrow = expanded ? "^" : "v";
            text(g, f, arrow, x + w - 52, y + 9, TEXT_DIM);

            int sx = x + w - 40;
            int sy = y + 7;
            fillRounded(g, sx, sy, 30, 12, 6, c(lerpColor(OFF_TRACK, ACCENT, anim)));
            fillRounded(g, sx + 2 + Math.round(anim * 18.0F), sy + 2, 8, 8, 4, c(0xFFFFFFFF));

            if (expanded) {
                g.fill(x + 8, y + HEADER, x + w - 8, y + HEADER + 1, c(OUTLINE));
                int cyy = y + HEADER + 6;
                for (Element e : visible()) {
                    e.x = x + 8;
                    e.y = cyy;
                    e.w = w - 16;
                    e.render(g, f, mx, my, inContent);
                    cyy += e.height() + GAP;
                }
            }
        }

        @Override
        boolean click(double mx, double my, int button) {
            if (my < y + HEADER) {
                if (button == 0) {
                    module.toggle(); // chuot trai: bat/tat
                } else if (button == 1) {
                    expanded = !expanded; // chuot phai: mo/dong setting
                }
                return false;
            }
            if (!expanded) {
                return false;
            }
            for (Element e : visible()) {
                if (e.hit(mx, my) && e.click(mx, my, button)) {
                    activeChild = e;
                    return true;
                }
            }
            return false;
        }

        @Override
        void drag(double mx) {
            if (activeChild != null) {
                activeChild.drag(mx);
            }
        }

        @Override
        void release() {
            if (activeChild != null) {
                activeChild.release();
                activeChild = null;
            }
        }

        @Override
        boolean typed(int codepoint) {
            for (Element e : children) {
                if (e.typed(codepoint)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        void cancel() {
            for (Element e : children) {
                e.cancel();
            }
        }

        @Override
        boolean key(int key) {
            for (Element e : children) {
                if (e.key(key)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Hien thi tam cho setting chua ho tro (xem TODO). */
    private final class InfoRow extends Element {
        private final Setting<?> setting;

        InfoRow(Setting<?> setting) {
            this.setting = setting;
        }

        @Override
        int height() {
            return 20;
        }

        @Override
        void render(GuiGraphics g, Font f, int mx, int my, boolean inContent) {
            fillRounded(g, x, y, w, 20, 4, c(0xFF1C1C20));
            text(g, f, setting.getName(), x + 8, y + 6, TEXT_DIM);
            String v = setting.serializeValue();
            text(g, f, v, x + w - 8 - f.width(v), y + 6, TEXT_DIM);
        }
    }

    /** Editor cho StringListSetting: danh sach id (block/item), go id + Enter de them, bam "x" de xoa. */
    private final class ListEditor extends Element {
        private static final int HEADER = 24;
        private static final int INPUT_H = 20;
        private static final int ROW = 18;
        private final StringListSetting setting;
        private boolean expanded;
        private boolean focused;
        private final StringBuilder input = new StringBuilder();

        ListEditor(StringListSetting setting) {
            this.setting = setting;
        }

        @Override
        int height() {
            if (!expanded) {
                return HEADER;
            }
            return HEADER + 4 + INPUT_H + 4 + setting.get().size() * ROW + 2;
        }

        @Override
        void render(GuiGraphics g, Font f, int mx, int my, boolean inContent) {
            fillRounded(g, x, y, w, height(), 5, c(hovered(mx, my, inContent) && my < y + HEADER ? CARD_HOVER : CARD));
            text(g, f, setting.getName(), x + 10, y + 8, TEXT);
            String right = setting.get().size() + (expanded ? "  ^" : "  v");
            text(g, f, right, x + w - 10 - f.width(right), y + 8, TEXT_DIM);
            if (!expanded) {
                return;
            }
            g.fill(x + 8, y + HEADER, x + w - 8, y + HEADER + 1, c(OUTLINE));

            int iy = y + HEADER + 4;
            fillRounded(g, x + 6, iy - 1, w - 12, INPUT_H + 2, 5, c(focused ? ACCENT : OUTLINE));
            fillRounded(g, x + 7, iy, w - 14, INPUT_H, 4, c(0xFF1A1A1E));
            if (input.length() == 0 && !focused) {
                text(g, f, "Type id + Enter...", x + 13, iy + 6, TEXT_DIM);
            } else {
                String shown = input.toString();
                int maxW = w - 30;
                while (f.width(shown) > maxW && shown.length() > 1) {
                    shown = shown.substring(1); // hien phan duoi
                }
                text(g, f, shown, x + 13, iy + 6, TEXT);
                if (focused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
                    int cxx = x + 13 + f.width(shown);
                    g.fill(cxx, iy + 5, cxx + 1, iy + 15, c(TEXT));
                }
            }

            int ry = iy + INPUT_H + 4;
            for (String entry : setting.get()) {
                boolean hov = inContent && mx >= x + 4 && mx < x + w - 4 && my >= ry && my < ry + ROW;
                if (hov) {
                    fillRounded(g, x + 4, ry, w - 8, ROW, 4, c(SELECT));
                }
                String shown = entry;
                while (f.width(shown) > w - 40 && shown.length() > 3) {
                    shown = shown.substring(0, shown.length() - 2);
                }
                text(g, f, shown, x + 12, ry + 5, TEXT);
                text(g, f, "x", x + w - 18, ry + 5, hov && mx >= x + w - 26 ? 0xFFFF6B6B : TEXT_DIM);
                ry += ROW;
            }
        }

        @Override
        boolean click(double mx, double my, int button) {
            if (button != 0) {
                return false;
            }
            if (my < y + HEADER) {
                expanded = !expanded;
                return false;
            }
            int iy = y + HEADER + 4;
            if (my >= iy && my < iy + INPUT_H) {
                focused = true;
                return false;
            }
            int idx = (int) ((my - (iy + INPUT_H + 4)) / ROW);
            List<String> list = setting.get();
            if (idx >= 0 && idx < list.size() && mx >= x + w - 26) {
                setting.remove(list.get(idx));
            }
            return false;
        }

        @Override
        void cancel() {
            focused = false;
        }

        @Override
        boolean key(int key) {
            if (!focused) {
                return false;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                focused = false;
            } else if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (input.length() > 0) {
                    input.setLength(input.length() - 1);
                }
            } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                String v = input.toString().trim();
                if (!v.isEmpty()) {
                    setting.add(v);
                    input.setLength(0);
                }
            }
            return true; // nuot moi phim khi dang go (ke ca Right Shift)
        }

        @Override
        boolean typed(int codepoint) {
            if (!focused) {
                return false;
            }
            if (codepoint >= 32 && codepoint < 127 && input.length() < 64) {
                input.append((char) codepoint);
            }
            return true;
        }
    }
}
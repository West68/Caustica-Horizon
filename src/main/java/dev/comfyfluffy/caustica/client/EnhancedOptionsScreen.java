package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Native scrollable settings, independent of Sodium's replacement Video Settings screen.
 * Pages are titled sections of one full-width control per row, each with its own reset button.
 */
public final class EnhancedOptionsScreen extends OptionsSubScreen {
    private static final int ROW_WIDTH = 310;
    private static final int RESET_WIDTH = 44;
    private static final int GAP = 4;
    private static final int CONTROL_WIDTH = ROW_WIDTH - RESET_WIDTH - GAP;

    private final String category;
    private boolean confirmResetAll;

    public EnhancedOptionsScreen(Screen parent) { this(parent, "home"); }

    public EnhancedOptionsScreen(Screen parent, String category) {
        super(parent, Minecraft.getInstance().options, Component.translatable("caustica.enhanced." + category));
        this.category = category;
    }

    @Override protected void addOptions() {
        if (category.equals("home")) {
            list.addHeader(Component.translatable("caustica.enhanced.current",
                Component.translatable("caustica.enhanced.current." + RtVideoOptions.visualPresetName())));
            list.addSmall(Button.builder(Component.translatable("caustica.enhanced.preset.itrp"), b -> applyPreset(true)).width(150).build(),
                Button.builder(Component.translatable("caustica.enhanced.preset.original"), b -> applyPreset(false)).width(150).build());
            if (!dev.comfyfluffy.caustica.CausticaBuild.RELEASE) {
                list.addBig(Button.builder(Component.translatable("caustica.enhanced.perfSweep"), b -> {
                    CausticaConfig.save(); minecraft.gui.setScreen(null); PerfSweep.start(); }).width(ROW_WIDTH).build());
                list.addBig(Button.builder(Component.translatable("caustica.enhanced.cinematic"), b -> {
                    CausticaConfig.save(); minecraft.gui.setScreen(null); CinematicDirector.start(); }).width(ROW_WIDTH).build());
            }
            list.addHeader(Component.translatable("caustica.enhanced.section.pages"));
            for (String page : RtVideoOptions.PAGES)
                list.addBig(Button.builder(Component.translatable("caustica.enhanced." + page),
                    b -> minecraft.gui.setScreen(new EnhancedOptionsScreen(this, page))).width(ROW_WIDTH).build());
            list.addBig(Button.builder(Component.translatable(confirmResetAll ? "caustica.enhanced.reset.allConfirm" : "caustica.enhanced.reset.all"), b -> {
                if (!confirmResetAll) { confirmResetAll = true; refresh(); return; }
                RtVideoOptions.PAGES.forEach(RtVideoOptions::resetPage);
                confirmResetAll = false;
                CausticaConfig.save();
                refresh();
            }).width(ROW_WIDTH).build());
            return;
        }
        for (RtVideoOptions.Section section : RtVideoOptions.sections(category)) {
            list.addHeader(Component.translatable(section.titleKey()));
            for (RtVideoOptions.Row row : section.rows()) {
                AbstractWidget control = row.option().createButton(options, 0, 0, CONTROL_WIDTH);
                list.addSmall(control, resetButton(row));
            }
        }
        if (category.equals("fg")) {
            list.addHeader(Component.translatable("caustica.enhanced.section.status"));
            for (String line : wrap(dev.comfyfluffy.caustica.rt.RtMfgDiagnostics.summary(), ROW_WIDTH)) list.addHeader(Component.literal(line));
        }
        list.addBig(Button.builder(Component.translatable("caustica.enhanced.reset.page"), b -> {
            RtVideoOptions.resetPage(category);
            CausticaConfig.save();
            refresh();
        }).width(ROW_WIDTH).build());
    }

    /** A row's reset button; OptionsList puts a row's second widget 160 px in, so it shifts itself to the row end. */
    private Button resetButton(RtVideoOptions.Row row) {
        Button button = new Button.Plain(0, 0, RESET_WIDTH, 20, Component.translatable("caustica.enhanced.reset.button"), b -> {
            row.reset();
            CausticaConfig.save();
            refresh();
        }, supplier -> Component.translatable("caustica.enhanced.reset.tooltip")) {
            @Override public void setPosition(int x, int y) {
                super.setPosition(x - 160 + CONTROL_WIDTH + GAP, y);
            }
        };
        boolean resettable = !row.settings().isEmpty();
        button.active = resettable && !row.isDefault();
        button.visible = resettable;
        if (resettable) button.setTooltip(Tooltip.create(Component.translatable("caustica.enhanced.reset.tooltip")));
        return button;
    }

    /**
     * Rebuild the page (widgets read the settings when created) while keeping the scroll position. A fresh screen,
     * not rebuildWidgets(): OptionsSubScreen keeps its final layout across init(), so every rebuild stacked another
     * options list on top of the old one (ghosted, overlapping buttons after applying a preset).
     */
    private void refresh() {
        double scroll = list == null ? 0 : list.scrollAmount();
        var next = new EnhancedOptionsScreen(lastScreen, category);
        next.confirmResetAll = confirmResetAll;
        minecraft.gui.setScreen(next);
        if (next.list != null) next.list.setScrollAmount(scroll);
    }

    private void applyPreset(boolean itrp) {
        RtVideoOptions.applyVisualPreset(itrp);
        refresh();
    }

    private List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n")) {
            String rest = paragraph;
            while (font.width(rest) > width) {
                String head = font.plainSubstrByWidth(rest, width);
                int cut = head.lastIndexOf(' ');
                if (cut > head.length() / 2) head = head.substring(0, cut);
                if (head.isEmpty()) break;
                lines.add(head);
                rest = rest.substring(head.length()).stripLeading();
            }
            if (!rest.isEmpty()) lines.add(rest);
        }
        return lines;
    }

    @Override public void removed() { CausticaConfig.save(); super.removed(); }
}

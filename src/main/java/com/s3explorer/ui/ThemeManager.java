package com.s3explorer.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.s3explorer.config.AppSettings;
import com.s3explorer.util.SystemTheme;

import javax.swing.UIManager;
import java.awt.Font;
import java.util.Map;

/** Applies the FlatLaf look and feel, theme and font size from settings. */
public final class ThemeManager {

    /** Brand blue from the S3 Explorer logo. */
    public static final String ACCENT = "#1F6FEB";

    private static boolean installed;

    private ThemeManager() {
    }

    public static void apply(AppSettings.Appearance appearance) {
        boolean animate = installed;
        if (animate) {
            FlatAnimatedLafChange.showSnapshot();
        }
        FlatLaf.setGlobalExtraDefaults(Map.of("@accentColor", ACCENT));
        UIManager.put("defaultFont", null);
        boolean dark = switch (appearance.theme) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> SystemTheme.isDark();
        };
        if (dark) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
        if (appearance.fontSize > 0) {
            Font base = UIManager.getFont("defaultFont");
            if (base != null) {
                UIManager.put("defaultFont", base.deriveFont((float) appearance.fontSize));
            }
        }
        UIManager.put("Table.rowHeight", appearance.tableDensity.rowHeight());
        UIManager.put("Tree.rowHeight", appearance.tableDensity.rowHeight());
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("Component.arc", 6);
        UIManager.put("Button.arc", 6);
        UIManager.put("TextComponent.arc", 6);
        UIManager.put("ProgressBar.arc", 6);
        UIManager.put("Table.showHorizontalLines", false);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.intercellSpacing", new java.awt.Dimension(0, 0));
        UIManager.put("TitlePane.unifiedBackground", true);
        if (installed) {
            FlatLaf.updateUI();
            FlatAnimatedLafChange.hideSnapshotWithAnimation();
        }
        installed = true;
    }

    public static boolean isDark() {
        return FlatLaf.isLafDark();
    }
}

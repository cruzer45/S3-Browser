package com.s3explorer.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.UIManager;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/** A two-column label/field form built on GridBagLayout. */
public class FormPanel extends JPanel {

    private int row;

    public FormPanel() {
        super(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
    }

    public FormPanel row(String label, JComponent field) {
        JLabel l = new JLabel(label);
        l.setLabelFor(field);
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0;
        lc.gridy = row;
        lc.anchor = GridBagConstraints.LINE_START;
        lc.insets = new Insets(4, 0, 4, 12);
        super.add(l, lc);

        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1;
        fc.gridy = row;
        fc.weightx = 1;
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.insets = new Insets(4, 0, 4, 0);
        super.add(field, fc);
        row++;
        return this;
    }

    /** A component spanning both columns. */
    public FormPanel wide(JComponent component) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 0, 4, 0);
        super.add(component, c);
        return this;
    }

    public FormPanel section(String title) {
        JLabel label = new JLabel(title);
        Font font = UIManager.getFont("Label.font");
        if (font != null) {
            label.setFont(font.deriveFont(Font.BOLD));
        }
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(row == 1 ? 0 : 12, 0, 2, 0);
        JPanel header = new JPanel(new GridBagLayout());
        header.setOpaque(false);
        GridBagConstraints h = new GridBagConstraints();
        header.add(label, h);
        h.gridx = 1;
        h.weightx = 1;
        h.fill = GridBagConstraints.HORIZONTAL;
        h.insets = new Insets(0, 8, 0, 0);
        header.add(new JSeparator(), h);
        super.add(header, c);
        return this;
    }

    /** Pushes rows to the top when the panel is taller than its content. */
    public FormPanel fillRemaining() {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row++;
        c.weighty = 1;
        super.add(new JPanel() {{ setOpaque(false); }}, c);
        return this;
    }
}

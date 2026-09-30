package com.s3explorer.ui.browser;

import com.s3explorer.model.S3ObjectItem;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/** Rows of the object browser. Every column's value is the item itself; renderers pick fields. */
public class ObjectTableModel extends AbstractTableModel {

    public static final int NAME = 0;
    public static final int SIZE = 1;
    public static final int MODIFIED = 2;
    public static final int TYPE = 3;
    public static final int STORAGE_CLASS = 4;
    public static final int ETAG = 5;

    private static final String[] COLUMNS = {"Name", "Size", "Last Modified", "Type", "Storage Class", "ETag"};

    private List<S3ObjectItem> items = new ArrayList<>();

    public void setItems(List<S3ObjectItem> newItems) {
        int oldSize = items.size();
        boolean appended = newItems.size() > oldSize && oldSize > 0 && newItems.subList(0, oldSize).equals(items);
        items = new ArrayList<>(newItems);
        if (appended) {
            fireTableRowsInserted(oldSize, items.size() - 1);
        } else {
            fireTableDataChanged();
        }
    }

    public S3ObjectItem get(int modelRow) {
        return items.get(modelRow);
    }

    public List<S3ObjectItem> items() {
        return items;
    }

    @Override
    public int getRowCount() {
        return items.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return S3ObjectItem.class;
    }

    @Override
    public Object getValueAt(int row, int column) {
        return items.get(row);
    }
}

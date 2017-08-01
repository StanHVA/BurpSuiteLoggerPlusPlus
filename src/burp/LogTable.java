package burp;

//
// extend JTable to handle cell selection and column move/resize
//

import burp.filter.ColorFilter;
import burp.filter.CompoundFilter;
import burp.filter.Filter;
import burp.filter.FilterCompiler;
import com.google.gson.Gson;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.plaf.UIResource;
import javax.swing.table.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.PrintWriter;
import java.util.*;
import java.util.List;

public class LogTable extends JTable
{

    public LogTable(List<LogEntry> data, PrintWriter stdout, PrintWriter stderr, boolean isDebug)
    {
        super(new LogTableModel(data), new LogTableColumnModel(stdout, stderr, isDebug));
        this.getModel().setColumnModel(this.getColumnModel());
        this.setTableHeader(new TableHeader (getColumnModel(),this,stdout,stderr,isDebug)); // This was used to create tool tips
        this.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); // to have horizontal scroll bar
        this.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); // selecting one row at a time
        this.setRowHeight(20); // As we are not using Burp customised UI, we have to define the row height to make it more pretty
        this.setDefaultRenderer(Boolean.class, new BooleanRenderer()); //Fix grey checkbox background
        ((JComponent) this.getDefaultRenderer(Boolean.class)).setOpaque(true); // to remove the white background of the checkboxes!

        // another way to detect column dragging to save its settings for next time loading! fooh! seems tricky!
        //			getLogTable().setTableHeader(new JTableHeader(getLogTable().getColumnModel()) {
        //				@Override
        //				public void setDraggedColumn(LogTableColumn column) {
        //					boolean finished = draggedColumn != null && column == null;
        //					super.setDraggedColumn(column);
        //					if (finished) {
        //						saveOrderTableChange(getLogTable(), getTableHeader());
        //
        //					}
        //				}
        //			});
        setRowSorter(new TableRowSorter<LogTableModel>(this.getModel()));

        registerListeners();
    }

    //Sneak in row coloring just before rendering the cell.
    @Override
    public Component prepareRenderer(TableCellRenderer renderer, int row, int column)
    {
        Component c = super.prepareRenderer(renderer, row, column);
        LogEntry entry = this.getModel().getRow(convertRowIndexToModel(row));

        if(this.getSelectedRow() == row){
            c.setBackground(this.getSelectionBackground());
            c.setForeground(this.getSelectionForeground());
        }else {
            if(entry.getMatchingColorFilters().size() != 0){
                ColorFilter colorFilter = null;
                Map<UUID, ColorFilter> colorFilters = BurpExtender.getInstance().getLoggerPreferences().getColorFilters();
                for (UUID uid : entry.getMatchingColorFilters()) {
                    if(colorFilter == null || colorFilter.getPriority() > colorFilters.get(uid).getPriority()){
                        colorFilter = colorFilters.get(uid);
                    }
                }
                if (colorFilter == null) {
                    c.setForeground(this.getForeground());
                    c.setBackground(this.getBackground());
                } else {
                    c.setForeground(colorFilter.getForegroundColor());
                    c.setBackground(colorFilter.getBackgroundColor());
                }
            }else{
                c.setForeground(this.getForeground());
                c.setBackground(this.getBackground());
            }
        }


        return c;
    }

    @Override
    public int convertColumnIndexToModel(int viewColumn) {
        return (int) this.getColumnModel().getColumnByViewLocation(viewColumn).getIdentifier();
    }

    @Override
    public int convertColumnIndexToView(int i) {
        return this.getColumnModel().getColumnViewLocation(i);
    }

    private void registerListeners(){
        this.addMouseListener( new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e) {
                if ( SwingUtilities.isRightMouseButton( e )) {
                    Point p = e.getPoint();
                    final int row = rowAtPoint(p);
                    final int col = columnAtPoint(p);
                    if (e.isPopupTrigger() && e.getComponent() instanceof JTable ) {
                        showContextMenu(e, row, col);
                    }
                }
            }

            @Override
            public void mouseReleased( MouseEvent e )
            {
                if ( SwingUtilities.isRightMouseButton( e ))
                {
                    Point p = e.getPoint();
                    final int row = convertRowIndexToModel(rowAtPoint(p));
                    final int col = convertColumnIndexToModel(columnAtPoint(p));
                    if (e.isPopupTrigger() && e.getComponent() instanceof JTable ) {
                        showContextMenu(e, row, col);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if ( SwingUtilities.isRightMouseButton( e ))
                {
                    Point p = e.getPoint();
                    final int row = convertRowIndexToModel(rowAtPoint(p));
                    final int col = convertColumnIndexToModel(columnAtPoint(p));
                    if (e.isPopupTrigger() && e.getComponent() instanceof JTable ) {
                        showContextMenu(e, row, col);
                    }
                }
            }

            private void showContextMenu(MouseEvent e, final int row, final int col){
                JPopupMenu popup = new JPopupMenu();
                JMenuItem useAsFilter = new JMenuItem(new AbstractAction("Use as filter") {
                    @Override
                    public void actionPerformed(ActionEvent actionEvent) {
                        String columnName = getColumnModel().getColumn(convertColumnIndexToView(col)).getName();
                        String value = "\"" + String.valueOf(getModel().getValueAt(row, col)) + "\"";
                        try {
                            Filter filter = new Filter(columnName, "==", value);
                            setFilter(filter, BurpExtender.getInstance().getFilterField());
                        } catch (Filter.FilterException e1) {return;}
                    }
                });
                popup.add(useAsFilter);

                if(getCurrentFilter() != null) {
                    JMenu addToCurrentFilter = new JMenu("Add To Filter");
                    JMenuItem andFilter = new JMenuItem(new AbstractAction("AND") {
                        @Override
                        public void actionPerformed(ActionEvent actionEvent) {
                            String columnName = getColumnModel().getColumn(convertColumnIndexToView(col)).getName();
                            String value = "\"" + String.valueOf(getModel().getValueAt(row, col)) + "\"";
                            try {
                                Filter rFilter = new Filter(columnName, "==", value);
                                Filter filter = new CompoundFilter(getCurrentFilter(), "&&", rFilter);
                                setFilter(filter, BurpExtender.getInstance().getFilterField());
                            } catch (Filter.FilterException e1) {
                                return;
                            }
                        }
                    });
                    JMenuItem orFilter = new JMenuItem(new AbstractAction("OR") {
                        @Override
                        public void actionPerformed(ActionEvent actionEvent) {
                            String columnName = getColumnModel().getColumn(convertColumnIndexToView(col)).getName();
                            String value = (String) getModel().getValueAt(row, col);
                            try {
                                Filter rFilter = new Filter(columnName, "==", value);
                                Filter filter = new CompoundFilter(getCurrentFilter(), "||", rFilter);
                                setFilter(filter, BurpExtender.getInstance().getFilterField());
                            } catch (Filter.FilterException e1) {
                                return;
                            }
                        }
                    });
                    addToCurrentFilter.add(andFilter);
                    addToCurrentFilter.add(orFilter);
                    popup.add(addToCurrentFilter);

                }

                JMenuItem colorFilterItem = new JMenuItem(new AbstractAction("Set as Color Filter") {
                    @Override
                    public void actionPerformed(ActionEvent actionEvent) {
                        String columnName = getColumnModel().getColumn(convertColumnIndexToView(col)).getName();
                        String value = (String) getModel().getValueAt(row, col);
                        try {
                            ColorFilter colorFilter = new ColorFilter();
                            colorFilter.setFilter(new Filter(columnName, "==", value));
                            BurpExtender.getInstance().addColorFilter(colorFilter, true);
                        } catch (Filter.FilterException e1) {
                            return;
                        }
                    }
                });
                popup.add(colorFilterItem);

                popup.show(e.getComponent(), e.getX(), e.getY());
            }

        });
    }

    private Filter getCurrentFilter(){
        return (Filter) ((TableRowSorter) this.getRowSorter()).getRowFilter();
    }

    public void setFilter(Filter filter){
        ((TableRowSorter) this.getRowSorter()).setRowFilter(filter);
    }

    public void setFilter(JTextComponent filterField){
        if(filterField.getText().length() == 0){
            setFilter(null, filterField);
        }else{
            try{
                Filter filter = FilterCompiler.parseString(filterField.getText());
                setFilter(filter);
                filterField.setBackground(Color.green);
            }catch (Filter.FilterException fException){
                setFilter((Filter) null);
                filterField.setBackground(Color.RED);
            }
        }
    }

    public void setFilter(Filter filter, JTextComponent filterField){
        if(filter == null){
            setFilter((Filter) null);
            filterField.setBackground(Color.white);
        } else {
            setFilter(filter);
            filterField.setText(filter.toString());
            filterField.setBackground(Color.green);
        }
    }

    @Override
    public void changeSelection(int row, int col, boolean toggle, boolean extend)
    {
        // show the log entry for the selected row
        // MoreHelp.showMessage("col: "+col+" - adjusted col: "+this.convertColumnIndexToModel(col) + " - " + this.convertColumnIndexToView(col));
        if(this.getModel().getData().size()>=row){
            LogEntry logEntry = this.getModel().getData().get(this.convertRowIndexToModel(row));
            BurpExtender.getInstance().getRequestViewer().setMessage(logEntry.requestResponse.getRequest(), true);
            if(logEntry.requestResponse.getResponse()!=null)
                BurpExtender.getInstance().getResponseViewer().setMessage(logEntry.requestResponse.getResponse(), false);
            else
                BurpExtender.getInstance().getResponseViewer().setMessage(new byte[0], false);
            this.getModel().setCurrentlyDisplayedItem(logEntry.requestResponse);

            super.changeSelection(row, col, toggle, extend);
        }
    }

    // to save the new table changes
    public void saveTableChanges(){
        // save it to the relevant variables and preferences
        this.getColumnModel().saveColumnJSON();
    }

    @Override
    public LogTableModel getModel(){
        return (LogTableModel) super.getModel();
    }

    @Override
    public LogTableColumnModel getColumnModel(){ return (LogTableColumnModel) super.getColumnModel(); }

    static class LeftTableCellRenderer extends DefaultTableCellRenderer {
        protected  LeftTableCellRenderer() {
            setHorizontalAlignment(SwingConstants.LEFT);
        }
    }
    static class JTableButtonRenderer implements TableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            JButton button = (JButton)value;
            return button;
        }
    }

    //Recreate boolean renderer to fix checkbox bug
    static class BooleanRenderer extends JCheckBox implements TableCellRenderer, UIResource {
        private static final Border noFocusBorder = new EmptyBorder(1, 1, 1, 1);

        public BooleanRenderer() {
            this.setHorizontalAlignment(0);
            this.setBorderPainted(true);
            this.setOpaque(true);
        }

        public Component getTableCellRendererComponent(JTable var1, Object var2, boolean var3, boolean var4, int var5, int var6) {
            if(var3) {
                this.setForeground(var1.getSelectionForeground());
                super.setBackground(var1.getSelectionBackground());
            } else {
                this.setForeground(var1.getForeground());
                this.setBackground(var1.getBackground());
            }

            this.setSelected(var2 != null && ((Boolean)var2).booleanValue());
            if(var4) {
                this.setBorder(UIManager.getBorder("LogTable.focusCellHighlightBorder"));
            } else {
                this.setBorder(noFocusBorder);
            }

            return this;
        }
    }

}
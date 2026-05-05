package tech.molecules.structurized.workbench.prism;

import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.Histogram;
import org.knowm.xchart.XChartPanel;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import tech.molecules.structurized.workbench.model.NumericEndpointAnalysis;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Numeric endpoint QC dashboard with summary tables and lightweight plots.
 */
public final class NumericEndpointDashboardPanel extends JPanel {
    private final JTabbedPane tabs = new JTabbedPane();

    public NumericEndpointDashboardPanel() {
        super(new BorderLayout());
        add(tabs, BorderLayout.CENTER);
        showEmpty("No numeric endpoint selected");
    }

    public void setAnalysis(NumericEndpointAnalysis analysis) {
        tabs.removeAll();
        if (analysis == null) {
            showEmpty("No numeric endpoint selected");
            return;
        }
        tabs.addTab("Summary", new JScrollPane(new JTable(new SummaryTableModel(analysis.summaryMap()))));
        tabs.addTab("Values", new JScrollPane(new JTable(new ValueTableModel(analysis.valueRows()))));
        tabs.addTab("Distribution", histogramPanel(analysis));
        tabs.addTab("Measurement Depth", rawCountPanel(analysis));
        tabs.addTab("Time", timePanel(analysis));
    }

    private void showEmpty(String text) {
        tabs.removeAll();
        tabs.addTab("Summary", new JLabel(text));
    }

    private static JPanel histogramPanel(NumericEndpointAnalysis analysis) {
        List<Double> values = analysis.valueRows().stream().map(NumericEndpointAnalysis.ValueRow::mean).toList();
        if (values.isEmpty()) {
            return messagePanel("No measured values");
        }
        int bins = Math.max(5, Math.min(30, (int) Math.ceil(Math.sqrt(values.size()))));
        Histogram histogram = new Histogram(values, bins);
        CategoryChart chart = new CategoryChartBuilder()
                .width(700)
                .height(420)
                .title(analysis.endpoint().getName())
                .xAxisTitle("Value")
                .yAxisTitle("Count")
                .build();
        chart.addSeries("Subjects", histogram.getxAxisData(), histogram.getyAxisData());
        return new XChartPanel<>(chart);
    }

    private static JPanel rawCountPanel(NumericEndpointAnalysis analysis) {
        List<String> subjects = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        analysis.valueRows().stream()
                .sorted(Comparator.comparingInt(NumericEndpointAnalysis.ValueRow::rawValueCount).reversed())
                .limit(80)
                .forEach(row -> {
                    subjects.add(row.subjectId());
                    counts.add(row.rawValueCount());
                });
        if (subjects.isEmpty()) {
            return messagePanel("No raw value counts");
        }
        CategoryChart chart = new CategoryChartBuilder()
                .width(700)
                .height(420)
                .title("Raw Values per Subject")
                .xAxisTitle("Subject")
                .yAxisTitle("Raw Values")
                .build();
        chart.addSeries("Raw values", subjects, counts);
        return new XChartPanel<>(chart);
    }

    private static JPanel timePanel(NumericEndpointAnalysis analysis) {
        List<NumericEndpointAnalysis.ValueRow> rows = analysis.valueRows().stream()
                .filter(row -> row.lastMeasurementInstant() != null || row.firstMeasurementInstant() != null)
                .sorted(Comparator.comparing(row -> effectiveInstant(row)))
                .toList();
        if (rows.isEmpty()) {
            return messagePanel("No parseable measurement dates");
        }
        double[] x = new double[rows.size()];
        double[] y = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            x[i] = effectiveInstant(rows.get(i)).toEpochMilli() / 86_400_000.0;
            y[i] = rows.get(i).mean();
        }
        XYChart chart = new XYChartBuilder()
                .width(700)
                .height(420)
                .title("Endpoint Value over Measurement Date")
                .xAxisTitle("Days since epoch")
                .yAxisTitle("Value")
                .build();
        chart.addSeries("Values", x, y);
        return new XChartPanel<>(chart);
    }

    private static Instant effectiveInstant(NumericEndpointAnalysis.ValueRow row) {
        return row.lastMeasurementInstant() != null ? row.lastMeasurementInstant() : row.firstMeasurementInstant();
    }

    private static JPanel messagePanel(String text) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel(text), BorderLayout.CENTER);
        return panel;
    }

    private static final class SummaryTableModel extends AbstractTableModel {
        private final List<Map.Entry<String, Object>> entries;

        private SummaryTableModel(Map<String, Object> values) {
            this.entries = new ArrayList<>(values.entrySet());
        }

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Metric" : "Value";
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Map.Entry<String, Object> entry = entries.get(rowIndex);
            return columnIndex == 0 ? entry.getKey() : entry.getValue();
        }
    }

    private static final class ValueTableModel extends AbstractTableModel {
        private final String[] columns = {"Subject", "Mean", "Lower", "Upper", "n", "Raw Values", "Raw IDs", "First", "Last"};
        private final List<NumericEndpointAnalysis.ValueRow> rows;

        private ValueTableModel(List<NumericEndpointAnalysis.ValueRow> rows) {
            this.rows = List.copyOf(rows);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            NumericEndpointAnalysis.ValueRow row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.subjectId();
                case 1 -> row.mean();
                case 2 -> row.lower();
                case 3 -> row.upper();
                case 4 -> row.n();
                case 5 -> row.rawValueCount();
                case 6 -> row.rawValueIdCount();
                case 7 -> row.firstMeasurement();
                case 8 -> row.lastMeasurement();
                default -> null;
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return switch (columnIndex) {
                case 1, 2, 3 -> Double.class;
                case 4, 5, 6 -> Integer.class;
                default -> Object.class;
            };
        }
    }
}

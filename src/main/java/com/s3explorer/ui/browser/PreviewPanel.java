package com.s3explorer.ui.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.s3explorer.app.AppContext;
import com.s3explorer.aws.S3Connection;
import com.s3explorer.config.JsonSupport;
import com.s3explorer.model.S3Path;
import com.s3explorer.service.PreviewService;
import com.s3explorer.util.Background;
import com.s3explorer.util.ErrorTranslator;
import com.s3explorer.util.FileSizeFormatter;
import com.s3explorer.util.MimeTypeUtils;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlRenderer;
import org.w3c.dom.Document;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Renders a preview of an object: images, plain text, pretty-printed JSON and XML, CSV as a
 * table, and Markdown as HTML (remote images are never fetched).
 */
public class PreviewPanel extends JPanel {

    private static final int MAX_CSV_ROWS = 2000;

    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final JLabel message = new JLabel("", SwingConstants.CENTER);
    private final ImageView image = new ImageView();
    private final JTextArea text = new JTextArea();
    private final JTable table = new JTable();
    private final JEditorPane html = new JEditorPane();
    private final JLabel banner = new JLabel();
    private int generation;

    public PreviewPanel() {
        super(new BorderLayout());
        message.setForeground(UIManager.getColor("Label.disabledForeground"));
        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("Label.font").getSize()));
        text.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        html.setEditable(false);
        html.setContentType("text/html");
        html.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        banner.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        banner.setVisible(false);

        body.add(message, "message");
        body.add(image, "image");
        body.add(new JScrollPane(text), "text");
        body.add(new JScrollPane(table), "table");
        body.add(new JScrollPane(html), "html");
        add(banner, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        showMessage("Select a file to preview");
    }

    public void showMessage(String text) {
        generation++;
        banner.setVisible(false);
        message.setText(text);
        cards.show(body, "message");
    }

    public void load(AppContext ctx, S3Connection connection, S3Path path) {
        if (!MimeTypeUtils.isPreviewable(path.name())) {
            showMessage("No preview for this file type");
            return;
        }
        int gen = ++generation;
        banner.setVisible(false);
        message.setText("Loading preview…");
        cards.show(body, "message");
        long limit = Math.max(16, ctx.settings.get().general.previewMaxKb) * 1024;
        Background.run(() -> {
            PreviewService.PreviewData data = ctx.preview.fetch(connection, path, limit);
            return prepare(data);
        }, rendered -> {
            if (gen == generation) {
                rendered.run();
            }
        }, error -> {
            if (gen == generation) {
                ErrorTranslator.UserMessage m = ErrorTranslator.translate(error, "read this object");
                message.setText("<html><center>" + escape(m.title()) + "<br>" + escape(m.message()) + "</center></html>");
                cards.show(body, "message");
            }
        });
    }

    /** Parses content off the EDT and returns the UI update to apply. */
    private Runnable prepare(PreviewService.PreviewData data) throws Exception {
        String name = data.path().name();
        String ext = MimeTypeUtils.extension(name);
        String bannerText = data.truncated()
                ? "Showing the first " + FileSizeFormatter.format(data.bytes().length) + " of "
                + FileSizeFormatter.format(data.totalSize())
                : null;

        if (MimeTypeUtils.isImagePreviewable(name)) {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data.bytes()));
            if (img == null) {
                return () -> showMessage("This image format cannot be displayed");
            }
            return () -> {
                image.setImage(img);
                setBanner(img.getWidth() + " × " + img.getHeight() + " px");
                cards.show(body, "image");
            };
        }

        String content = new String(data.bytes(), StandardCharsets.UTF_8);
        if (("csv".equals(ext) || "tsv".equals(ext))) {
            List<List<String>> rows = parseDelimited(content, "tsv".equals(ext) ? '\t' : ',');
            return () -> {
                showTable(rows);
                setBanner(bannerText != null ? bannerText : rows.size() > MAX_CSV_ROWS ? "Showing first "
                        + MAX_CSV_ROWS + " rows" : null);
                cards.show(body, "table");
            };
        }
        if (("md".equals(ext) || "markdown".equals(ext))) {
            String rendered = renderMarkdown(content);
            return () -> {
                html.setText(rendered);
                html.setCaretPosition(0);
                setBanner(bannerText);
                cards.show(body, "html");
            };
        }
        String display = content;
        if ("json".equals(ext) && !data.truncated()) {
            display = prettyJson(content);
        } else if ("xml".equals(ext) && !data.truncated()) {
            display = prettyXml(content);
        }
        String finalText = display;
        return () -> {
            text.setText(finalText);
            text.setCaretPosition(0);
            setBanner(bannerText);
            cards.show(body, "text");
        };
    }

    private void setBanner(String value) {
        banner.setText(value);
        banner.setVisible(value != null);
    }

    private void showTable(List<List<String>> rows) {
        DefaultTableModel model = new DefaultTableModel() {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        if (!rows.isEmpty()) {
            int cols = rows.stream().limit(MAX_CSV_ROWS).mapToInt(List::size).max().orElse(0);
            List<String> header = rows.get(0);
            for (int c = 0; c < cols; c++) {
                model.addColumn(c < header.size() ? header.get(c) : "Column " + (c + 1));
            }
            for (int r = 1; r < Math.min(rows.size(), MAX_CSV_ROWS); r++) {
                model.addRow(rows.get(r).toArray());
            }
        }
        table.setModel(model);
        for (int c = 0; c < table.getColumnCount(); c++) {
            table.getColumnModel().getColumn(c).setPreferredWidth(140);
        }
    }

    /** Minimal RFC 4180 parser: quoted fields, escaped quotes and embedded newlines. */
    static List<List<String>> parseDelimited(String content, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < content.length() && rows.size() <= MAX_CSV_ROWS; i++) {
            char c = content.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"' && field.isEmpty()) {
                quoted = true;
            } else if (c == delimiter) {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    static String prettyJson(String content) {
        try {
            JsonNode node = JsonSupport.MAPPER.readTree(content);
            return JsonSupport.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return content;
        }
    }

    static String prettyXml(String content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Never resolve external entities or DTDs from untrusted content.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                    content.getBytes(StandardCharsets.UTF_8)));
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            return content;
        }
    }

    static String renderMarkdown(String content) {
        Parser parser = Parser.builder().build();
        HtmlRenderer renderer = HtmlRenderer.builder()
                .escapeHtml(true)
                .sanitizeUrls(true)
                // Replace images with their alt text so previews never fetch remote content.
                .nodeRendererFactory(context -> new NodeRenderer() {
                    @Override
                    public Set<Class<? extends Node>> getNodeTypes() {
                        return Set.of(Image.class);
                    }

                    @Override
                    public void render(Node node) {
                        StringBuilder alt = new StringBuilder();
                        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                            if (child instanceof org.commonmark.node.Text t) {
                                alt.append(t.getLiteral());
                            }
                        }
                        context.getWriter().text("[image" + (alt.isEmpty() ? "" : ": " + alt) + "]");
                    }
                })
                .build();
        return "<html><body style='margin:8px'>" + renderer.render(parser.parse(content)) + "</body></html>";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Paints an image scaled down to fit, preserving aspect ratio. */
    private static final class ImageView extends JComponent {
        private BufferedImage img;

        void setImage(BufferedImage img) {
            this.img = img;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (img == null) {
                return;
            }
            double scale = Math.min(1.0, Math.min((getWidth() - 16.0) / img.getWidth(),
                    (getHeight() - 16.0) / img.getHeight()));
            int w = (int) (img.getWidth() * scale);
            int h = (int) (img.getHeight() * scale);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(img, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            g2.dispose();
        }
    }
}

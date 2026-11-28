import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Renders the logo card shown above each alert (issue #10): one 1200×600 PNG per company, sharp at any phone size.
 *
 * <ul>
 *   <li>A wordmark (a wide logo that already spells the name) is centred on its own.</li>
 *   <li>An icon (square-ish) is centred with the company name written underneath.</li>
 *   <li>No usable image: the name alone, large.</li>
 * </ul>
 *
 * <p>Usage: {@code java scripts/logos/LogoCards.java <seed-boards.csv> <raw image dir> <output dir>}. The raw images
 * are named {@code <domain>.png} or {@code <domain>.jpg}; see {@code fetch_images.py} for how they are found.
 */
public class LogoCards {

    static final int W = 1200, H = 600;
    static final Color BACKGROUND = Color.WHITE;
    static final Color DARK_BACKGROUND = new Color(0x15171C);
    static final Color INK = new Color(0x16181D);
    /** Wider than this (after trimming margins) and the logo is treated as a wordmark that already shows the name. */
    static final double WORDMARK_RATIO = 2.2;
    /** Found images that would mislead: Wikidata's logo for Nium is its Instarem brand. These get a name card. */
    static final java.util.Set<String> NAME_ONLY = java.util.Set.of("nium.com");

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[2]);
        Files.createDirectories(out);
        int wordmarks = 0, icons = 0, textOnly = 0;
        for (String[] board : boards(Path.of(args[0]))) {
            String domain = board[2], company = board[3];
            BufferedImage logo = NAME_ONLY.contains(domain) ? null : read(Path.of(args[1]), domain);
            BufferedImage card;
            String kind;
            if (logo == null) {
                card = textCard(company);
                kind = "text";
                textOnly++;
            } else {
                logo = trim(logo);
                double ratio = (double) logo.getWidth() / logo.getHeight();
                if (ratio >= WORDMARK_RATIO) {
                    card = wordmarkCard(logo);
                    kind = "wordmark";
                    wordmarks++;
                } else {
                    card = iconCard(logo, company);
                    kind = "icon+name";
                    icons++;
                }
            }
            ImageIO.write(card, "png", out.resolve(domain + ".png").toFile());
            System.out.printf("%-28s %s%n", domain, kind);
        }
        System.out.printf("%d wordmarks, %d icons with names, %d text-only%n", wordmarks, icons, textOnly);
    }

    static List<String[]> boards(Path csv) throws IOException {
        List<String[]> boards = new ArrayList<>();
        for (String line : Files.readAllLines(csv, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#") || line.startsWith("source,")) {
                continue;
            }
            String[] p = line.split(",", 4);
            if (!p[2].isBlank()) {
                boards.add(p);
            }
        }
        return boards;
    }

    static BufferedImage read(Path dir, String domain) throws IOException {
        for (String ext : List.of("png", "jpg")) {
            Path p = dir.resolve(domain + "." + ext);
            if (Files.exists(p)) {
                BufferedImage img = ImageIO.read(p.toFile());
                if (img != null && Math.min(img.getWidth(), img.getHeight()) >= 150) {
                    return toArgb(img);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------ layouts

    static BufferedImage wordmarkCard(BufferedImage logo) {
        boolean light = mostlyLight(logo);
        BufferedImage card = canvas(light ? DARK_BACKGROUND : BACKGROUND);
        Graphics2D g = graphics(card);
        drawFitted(g, logo, W / 2, H / 2, 780, 230);
        g.dispose();
        return card;
    }

    /** Icons sit on white like everywhere else: a square icon carries its own colours, unlike a bare white wordmark. */
    static BufferedImage iconCard(BufferedImage icon, String company) {
        BufferedImage card = canvas(BACKGROUND);
        Graphics2D g = graphics(card);
        drawFitted(g, icon, W / 2, 235, 240, 240);
        drawName(g, company, 470, 76, INK);
        g.dispose();
        return card;
    }

    static BufferedImage textCard(String company) {
        BufferedImage card = canvas(BACKGROUND);
        Graphics2D g = graphics(card);
        drawName(g, company, H / 2 + 40, 120, INK);
        g.dispose();
        return card;
    }

    // ------------------------------------------------------------------------------------ drawing

    static void drawFitted(Graphics2D g, BufferedImage img, int cx, int cy, int maxW, int maxH) {
        double scale = Math.min((double) maxW / img.getWidth(), (double) maxH / img.getHeight());
        int w = (int) Math.round(img.getWidth() * scale), h = (int) Math.round(img.getHeight() * scale);
        g.drawImage(scaled(img, w, h), cx - w / 2, cy - h / 2, null);
    }

    /** The name in Segoe UI Semibold, shrunk until it fits the card's width. Baseline at {@code baseline}. */
    static void drawName(Graphics2D g, String name, int baseline, int size, Color color) {
        Font font;
        FontMetrics fm;
        do {
            font = new Font("Segoe UI Semibold", Font.PLAIN, size);
            fm = g.getFontMetrics(font);
            size -= 4;
        } while (fm.stringWidth(name) > W - 160 && size > 30);
        g.setFont(font);
        g.setColor(color);
        g.drawString(name, (W - fm.stringWidth(name)) / 2, baseline);
    }

    /** Smooth downscaling in halving steps; a single bilinear step from 1200 px to 240 px would alias. */
    static BufferedImage scaled(BufferedImage img, int w, int h) {
        BufferedImage cur = img;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            cur = resize(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        }
        return resize(cur, w, h);
    }

    static BufferedImage resize(BufferedImage img, int w, int h) {
        BufferedImage out = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(out);
        g.drawImage(img, 0, 0, out.getWidth(), out.getHeight(), null);
        g.dispose();
        return out;
    }

    static BufferedImage canvas(Color background) {
        BufferedImage card = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = card.createGraphics();
        g.setColor(background);
        g.fillRect(0, 0, W, H);
        g.dispose();
        return card;
    }

    static Graphics2D graphics(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    // ------------------------------------------------------------------------------------ analysis

    static BufferedImage toArgb(BufferedImage img) {
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Crops transparent or near-white margins, so the logo's real shape decides wordmark vs icon. */
    static BufferedImage trim(BufferedImage img) {
        int minX = img.getWidth(), minY = img.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (!blank(img.getRGB(x, y))) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) {
            return img;
        }
        return img.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    static boolean blank(int argb) {
        int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return a < 16 || (r > 245 && g > 245 && b > 245);
    }

    /** True if the visible pixels are nearly all very light: a white logo meant for a dark background. */
    static boolean mostlyLight(BufferedImage img) {
        long visible = 0, light = 0;
        for (int y = 0; y < img.getHeight(); y += 2) {
            for (int x = 0; x < img.getWidth(); x += 2) {
                int argb = img.getRGB(x, y);
                if ((argb >>> 24) < 128) {
                    continue;
                }
                visible++;
                int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                if (r > 225 && g > 225 && b > 225) {
                    light++;
                }
            }
        }
        // An opaque image (a JPEG, or a PNG with a white square) is never "light": its white is a background.
        boolean hasTransparency = visible < (long) (img.getWidth() / 2) * (img.getHeight() / 2) * 0.98;
        return hasTransparency && visible > 0 && light > visible * 0.85;
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}

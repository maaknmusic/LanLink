package lanlink.desktop;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** The LanLink icon (a monitor and a phone), drawn in code. Also writes the .ico used for the exe. */
final class Icons {
    private Icons() {}

    private static final Color BLUE = new Color(0x2563EB), LIGHT = new Color(0x93C5FD);

    static BufferedImage draw(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 108.0, size / 108.0);
        g.setColor(BLUE);
        g.fill(new RoundRectangle2D.Double(4, 4, 100, 100, 40, 40));
        // Monitor
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(16, 26, 58, 40, 8, 8));
        g.fill(new RoundRectangle2D.Double(39, 66, 12, 9, 2, 2));
        g.fill(new RoundRectangle2D.Double(30, 74, 30, 5, 4, 4));
        g.setColor(LIGHT);
        g.fill(new RoundRectangle2D.Double(21, 31, 48, 30, 4, 4));
        // Phone, outlined in blue so it stands apart from the monitor
        g.setColor(BLUE);
        g.fill(new RoundRectangle2D.Double(60, 38, 32, 52, 12, 12));
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(64, 42, 24, 44, 8, 8));
        g.setColor(LIGHT);
        g.fill(new RoundRectangle2D.Double(68, 48, 16, 28, 3, 3));
        g.setColor(BLUE);
        g.fillOval(74, 79, 4, 4);
        g.dispose();
        return img;
    }

    static List<Image> windowIcons() {
        List<Image> out = new ArrayList<>();
        for (int s : new int[] {16, 24, 32, 48, 64, 128, 256}) out.add(draw(s));
        return out;
    }

    /** Build step: writes a multi-size PNG-compressed .ico to args[0]. */
    public static void main(String[] args) throws IOException {
        int[] sizes = {16, 24, 32, 48, 64, 256};
        List<byte[]> pngs = new ArrayList<>();
        for (int s : sizes) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ImageIO.write(draw(s), "png", b);
            pngs.add(b.toByteArray());
        }
        int offset = 6 + 16 * sizes.length;
        ByteBuffer head = ByteBuffer.allocate(offset).order(ByteOrder.LITTLE_ENDIAN);
        head.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            head.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0);
            head.putShort((short) 1).putShort((short) 32).putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        try (FileOutputStream out = new FileOutputStream(args[0])) {
            out.write(head.array());
            for (byte[] p : pngs) out.write(p);
        }
    }
}

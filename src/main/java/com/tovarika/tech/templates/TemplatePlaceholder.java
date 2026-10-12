package com.tovarika.tech.templates;

import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

public final class TemplatePlaceholder {
    public static final int WIDTH = 768;
    public static final int HEIGHT = 1024;
    private static final byte[] PNG = render();

    private TemplatePlaceholder() {}

    public static byte[] png() {
        return PNG.clone();
    }

    private static byte[] render() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(244, 246, 249));
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            graphics.setColor(new Color(222, 227, 234));
            graphics.fillRoundRect(92, 196, 584, 632, 48, 48);
            graphics.setColor(new Color(177, 187, 199));
            graphics.fillOval(264, 342, 240, 240);
            graphics.setColor(new Color(71, 79, 89));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
            graphics.drawString("TOVARIKA", 298, 690);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 21));
            graphics.drawString("template placeholder 3:4", 255, 728);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }
}

package com.tovarika.tech.images.application;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

public final class ImageRaster {
    private ImageRaster() {}
    public static BufferedImage decode(byte[] bytes) {
        if(bytes.length==0 || bytes.length>10_485_760) throw new IllegalArgumentException("Invalid image size");
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext()) throw new IllegalArgumentException("Unsupported image");
            var reader=readers.next();
            try {
                reader.setInput(input);
                int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<=0 || h<=0 || w>4096 || h>4096 || (long)w*h>8_388_608)
                    throw new IllegalArgumentException("Image dimensions exceed limits");
                return reader.read(0);
            } finally { reader.dispose(); }
        } catch(IOException invalid) { throw new IllegalArgumentException("Cannot decode image"); }
    }
    public static byte[] png(BufferedImage image) {
        try(var out=new ByteArrayOutputStream()) {
            if(!ImageIO.write(image,"png",out)) throw new IllegalStateException("PNG encoder unavailable");
            byte[] bytes=out.toByteArray();
            if(bytes.length>10_485_760) throw new IllegalArgumentException("Image result exceeds limit");
            return bytes;
        } catch(IOException failure) { throw new IllegalStateException("Cannot encode PNG"); }
    }
    public static boolean hasAlpha(BufferedImage image) {
        if(!image.getColorModel().hasAlpha()) return false;
        for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++)
            if((image.getRGB(x,y)>>>24)<255) return true;
        return false;
    }
}

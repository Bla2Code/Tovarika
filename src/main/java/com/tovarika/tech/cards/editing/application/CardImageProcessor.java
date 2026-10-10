package com.tovarika.tech.cards.editing.application;

import com.tovarika.tech.cards.editing.domain.ImageEdit;
import com.tovarika.tech.images.application.ImageEditInput;
import com.tovarika.tech.images.application.ImageGenerationService;
import com.tovarika.tech.images.application.ImageRaster;
import java.awt.AlphaComposite;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import org.springframework.stereotype.Service;

@Service
public class CardImageProcessor {
    private static final String ERASE_PROMPT = "Remove the objects and object fragments inside the transparent mask. "
            + "Reconstruct the surrounding background naturally in their place, matching texture, lighting, perspective "
            + "and existing transparency. Preserve every unmasked product, text and graphic. "
            + "Do not add replacement objects or blur the selected area. Keep the same canvas.";
    public record Result(byte[] bytes,int width,int height,boolean hasAlpha) {}
    private final ImageGenerationService images;
    public CardImageProcessor(ImageGenerationService images) { this.images=images; }
    public Result process(byte[] bytes,ImageEdit edit) {
        edit.validate();
        var source=ImageRaster.decode(bytes);
        BufferedImage result;
        switch(edit.kind()) {
            case "entire" -> result=ai(source,edit.prompt(),new byte[0],false);
            case "region" -> {
                var r=edit.rect();int w=source.getWidth(),h=source.getHeight();
                int left=(int)Math.floor(r.x()*w),top=(int)Math.floor(r.y()*h);
                int right=Math.min(w,(int)Math.ceil((r.x()+r.width())*w));
                int bottom=Math.min(h,(int)Math.ceil((r.y()+r.height())*h));
                if(right<=left || bottom<=top) throw new IllegalArgumentException("Empty pixel rectangle");
                var mask=opaqueMask(w,h);
                for(int y=top;y<bottom;y++) for(int x=left;x<right;x++) mask.setRGB(x,y,0);
                var edited=ai(source,edit.prompt(),ImageRaster.png(mask),false);
                result=copy(source);
                for(int y=top;y<bottom;y++) for(int x=left;x<right;x++) result.setRGB(x,y,edited.getRGB(x,y));
            }
            case "erase" -> {
                var mask=BrushMaskRasterizer.rasterize(source.getWidth(),source.getHeight(),edit.mask());
                var edited=ai(source,ERASE_PROMPT,ImageRaster.png(mask),false);
                result=copy(source);
                // Provider masks guide generation; this merge is the pixel preservation guarantee.
                for(int y=0;y<source.getHeight();y++) for(int x=0;x<source.getWidth();x++)
                    if((mask.getRGB(x,y)>>>24)==0) result.setRGB(x,y,edited.getRGB(x,y));
            }
            case "remove_background" -> {
                String instruction="product_only".equals(edit.foreground())
                        ? "Remove the background and all overlay text and decorative graphics. Preserve only the product."
                        : "Remove only the background. Preserve the product, all overlay text and foreground graphic elements.";
                result=ai(source,instruction+" Keep the same canvas and produce true transparency, without a matte.",new byte[0],true);
                if(!ImageRaster.hasAlpha(result)) throw new IllegalArgumentException("Background removal produced no transparency");
            }
            case "resize" -> result=resize(source,edit.aspectRatio(),edit.mode());
            default -> throw new IllegalArgumentException("Unsupported operation");
        }
        return new Result(ImageRaster.png(result),result.getWidth(),result.getHeight(),ImageRaster.hasAlpha(result));
    }
    private BufferedImage ai(BufferedImage source,String prompt,byte[] mask,boolean transparent) {
        var generated=images.edit("chatgpt",new ImageEditInput(ImageRaster.png(source),"image/png",prompt,
                source.getWidth(),source.getHeight(),mask,transparent));
        var result=ImageRaster.decode(generated.bytes());
        if(result.getWidth()!=source.getWidth() || result.getHeight()!=source.getHeight())
            throw new IllegalArgumentException("Edited image dimensions do not match source");
        return result;
    }
    private BufferedImage resize(BufferedImage source,String ratio,String mode) {
        int[] size=switch(ratio) {
            case "1:1" -> new int[]{1024,1024};case "3:4" -> new int[]{1152,1536};
            case "4:5" -> new int[]{1024,1280};case "16:9" -> new int[]{1536,864};
            default -> throw new IllegalArgumentException("Unsupported ratio");
        };
        int w=size[0],h=size[1];
        double scale="crop".equals(mode)?Math.max((double)w/source.getWidth(),(double)h/source.getHeight())
                :Math.min((double)w/source.getWidth(),(double)h/source.getHeight());
        int sw=Math.max(1,(int)Math.round(source.getWidth()*scale)),sh=Math.max(1,(int)Math.round(source.getHeight()*scale));
        int left=(w-sw)/2,top=(h-sh)/2;
        var fitted=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        var g=fitted.createGraphics();
        try { g.setComposite(AlphaComposite.Src);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(source,left,top,sw,sh,null); } finally { g.dispose(); }
        if(!"outpaint".equals(mode) || (sw==w && sh==h)) return fitted;
        var mask=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        for(int y=top;y<top+sh;y++) for(int x=left;x<left+sw;x++) mask.setRGB(x,y,0xff000000);
        var extended=ai(fitted,"Extend the surrounding background naturally into the transparent canvas margins. "
                +"Do not alter the existing card, text, product or graphics inside the protected area.",ImageRaster.png(mask),false);
        // Keep the fitted card exact; the provider's mask is guidance rather than a pixel guarantee.
        for(int y=top;y<top+sh;y++) for(int x=left;x<left+sw;x++) extended.setRGB(x,y,fitted.getRGB(x,y));
        return extended;
    }
    private BufferedImage opaqueMask(int w,int h) {
        var mask=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) mask.setRGB(x,y,0xff000000);
        return mask;
    }
    private BufferedImage copy(BufferedImage source) {
        var copy=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_ARGB);
        copy.setRGB(0,0,source.getWidth(),source.getHeight(),source.getRGB(0,0,source.getWidth(),source.getHeight(),null,0,source.getWidth()),0,source.getWidth());
        return copy;
    }
}

package com.tovarika.tech.cards.editing.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tovarika.tech.cards.editing.domain.ImageEdit;
import com.tovarika.tech.images.application.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CardImageProcessorTest {
    ImageGenerationService images=mock(ImageGenerationService.class);
    CardImageProcessor processor=new CardImageProcessor(images);

    @Test void regionBuildsMaskFromSourcePixelsAndCompositesOutsideExactly() {
        var source=raster(8,6,0x80334455);
        source.setRGB(0,0,0x00112233);
        var captured=new AtomicReference<ImageEditInput>();
        when(images.edit(eq("chatgpt"),any(ImageEditInput.class))).thenAnswer(call->{
            var input=call.getArgument(1,ImageEditInput.class);captured.set(input);
            return generated(raster(8,6,0xffabcdef));
        });
        var result=ImageRaster.decode(processor.process(ImageRaster.png(source),
                new ImageEdit("region","Replace this area",new ImageEdit.Rect(.2,.2,.3,.4),null,null,null)).bytes());
        var mask=ImageRaster.decode(captured.get().mask());
        var submitted=ImageRaster.decode(captured.get().original());
        for(int y=0;y<6;y++) for(int x=0;x<8;x++) {
            boolean inside=x>=1 && x<4 && y>=1 && y<4;
            assertThat(result.getRGB(x,y)).isEqualTo(inside?0xffabcdef:source.getRGB(x,y));
            assertThat(mask.getRGB(x,y)>>>24).isEqualTo(inside?0:255);
            assertThat(submitted.getRGB(x,y)).isEqualTo(source.getRGB(x,y));
        }
        assertThat(captured.get().width()).isEqualTo(8);
        assertThat(captured.get().height()).isEqualTo(6);
    }

    @Test void eraseUsesActualBrushUnionAndPreservesEveryUnselectedRgbaPixel() {
        var source=raster(120,80,0x80334455);
        source.setRGB(0,0,0x00112233);
        var selection=new ImageEdit.BrushMask("brush",java.util.List.of(
            new ImageEdit.Stroke(.05,java.util.List.of(new ImageEdit.Point(.2,.2),new ImageEdit.Point(.2,.7),new ImageEdit.Point(.5,.7))),
            new ImageEdit.Stroke(.05,java.util.List.of(new ImageEdit.Point(.8,.2)))));
        var captured=new AtomicReference<ImageEditInput>();
        when(images.edit(eq("chatgpt"),any(ImageEditInput.class))).thenAnswer(call->{
            captured.set(call.getArgument(1,ImageEditInput.class));
            return generated(raster(120,80,0xffabcdef));
        });
        var output=processor.process(ImageRaster.png(source),new ImageEdit("erase",null,null,null,null,null,selection));
        var result=ImageRaster.decode(output.bytes());
        var mask=ImageRaster.decode(captured.get().mask());
        assertThat(captured.get().transparentBackground()).isFalse();
        assertThat(captured.get().prompt()).contains("Remove the objects", "Reconstruct the surrounding background", "Preserve every unmasked");
        assertThat(output.width()).isEqualTo(120);assertThat(output.height()).isEqualTo(80);
        assertThat(output.hasAlpha()).isTrue();
        assertThat(mask.getRGB(24,16)>>>24).isZero();
        assertThat(mask.getRGB(24,56)>>>24).isZero();
        assertThat(mask.getRGB(60,56)>>>24).isZero();
        assertThat(mask.getRGB(96,16)>>>24).isZero();
        // The unpainted middle of an L stroke and the gap between strokes remain protected.
        assertThat(mask.getRGB(48,32)>>>24).isEqualTo(255);
        assertThat(mask.getRGB(72,16)>>>24).isEqualTo(255);
        int changed=0;
        for(int y=0;y<80;y++) for(int x=0;x<120;x++) {
            int alpha=mask.getRGB(x,y)>>>24;
            assertThat(alpha).isIn(0,255);
            assertThat(result.getRGB(x,y)).isEqualTo(alpha==0?0xffabcdef:source.getRGB(x,y));
            if(alpha==0) changed++;
        }
        assertThat(changed).isGreaterThan(100).isLessThan(1500);
    }

    @Test void brushRadiusUsesShorterSideAndClipsRoundFootprintAtEdges() {
        var mask=BrushMaskRasterizer.rasterize(200,100,new ImageEdit.BrushMask("brush",java.util.List.of(
            new ImageEdit.Stroke(.1,java.util.List.of(new ImageEdit.Point(0,0))),
            new ImageEdit.Stroke(.1,java.util.List.of(new ImageEdit.Point(.5,.5))))));
        assertThat(mask.getRGB(0,0)>>>24).isZero();
        assertThat(mask.getRGB(5,5)>>>24).isZero();
        assertThat(mask.getRGB(9,9)>>>24).isEqualTo(255);
        assertThat(mask.getRGB(108,50)>>>24).isZero();
        assertThat(mask.getRGB(100,58)>>>24).isZero();
        assertThat(mask.getRGB(115,50)>>>24).isEqualTo(255);
    }

    @Test void invalidOrSubpixelEmptyBrushMaskNeverInvokesAi() {
        for(var mask:java.util.List.of(
            new ImageEdit.BrushMask("brush",java.util.List.of()),
            new ImageEdit.BrushMask("brush",java.util.List.of(new ImageEdit.Stroke(.1,java.util.List.of(new ImageEdit.Point(Double.NaN,.5))))),
            new ImageEdit.BrushMask("brush",java.util.List.of(new ImageEdit.Stroke(.3,java.util.List.of(new ImageEdit.Point(.5,.5))))),
            new ImageEdit.BrushMask("brush",java.util.List.of(new ImageEdit.Stroke(.1,java.util.Collections.nCopies(1025,new ImageEdit.Point(.5,.5))))),
            new ImageEdit.BrushMask("brush",java.util.Collections.nCopies(9,new ImageEdit.Stroke(.1,java.util.Collections.nCopies(1024,new ImageEdit.Point(.5,.5))))))) {
            assertThatThrownBy(()->processor.process(ImageRaster.png(raster(120,80,0xff112233)),
                new ImageEdit("erase",null,null,null,null,null,mask))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->BrushMaskRasterizer.rasterize(1,1,new ImageEdit.BrushMask("brush",java.util.List.of(
            new ImageEdit.Stroke(.002,java.util.List.of(new ImageEdit.Point(1,1)))))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no source pixels");
        verifyNoInteractions(images);
    }

    @Test void fitAndCropHaveExplicitDimensionsAndNeverInvokeAi() {
        byte[] source=ImageRaster.png(raster(8,4,0xff112233));
        var fitted=processor.process(source,new ImageEdit("resize",null,null,null,"1:1","fit_pad"));
        var cropped=processor.process(source,new ImageEdit("resize",null,null,null,"4:5","crop"));
        assertThat(fitted.width()).isEqualTo(1024);assertThat(fitted.height()).isEqualTo(1024);
        assertThat(fitted.hasAlpha()).isTrue();
        assertThat(ImageRaster.decode(fitted.bytes()).getRGB(0,0)>>>24).isZero();
        assertThat(cropped.width()).isEqualTo(1024);assertThat(cropped.height()).isEqualTo(1280);
        assertThat(cropped.hasAlpha()).isFalse();
        verifyNoInteractions(images);
    }

    @Test void outpaintPreservesFittedContentEvenIfProviderChangesIt() {
        when(images.edit(eq("chatgpt"),any(ImageEditInput.class))).thenAnswer(call->{
            var input=call.getArgument(1,ImageEditInput.class);
            assertThat(ImageRaster.decode(input.mask()).getRGB(512,512)>>>24).isEqualTo(255);
            assertThat(ImageRaster.decode(input.mask()).getRGB(0,0)>>>24).isZero();
            return generated(raster(input.width(),input.height(),0xffabcdef));
        });
        var result=ImageRaster.decode(processor.process(ImageRaster.png(raster(8,4,0xff112233)),
                new ImageEdit("resize",null,null,null,"1:1","outpaint")).bytes());
        assertThat(result.getRGB(512,512)).isEqualTo(0xff112233);
        assertThat(result.getRGB(0,0)).isEqualTo(0xffabcdef);
    }

    @Test void removalRequiresActualAlphaAndKeepsItInPng() {
        when(images.edit(eq("chatgpt"),any(ImageEditInput.class))).thenAnswer(call->{
            var input=call.getArgument(1,ImageEditInput.class);
            assertThat(input.transparentBackground()).isTrue();
            assertThat(input.prompt()).contains("overlay text");
            return generated(raster(8,6,0xff112233));
        });
        var edit=new ImageEdit("remove_background",null,null,"preserve_graphics",null,null);
        var source=ImageRaster.png(raster(8,6,0xff112233));
        assertThatThrownBy(()->processor.process(source,edit)).hasMessageContaining("no transparency");
        var transparent=raster(8,6,0xff112233);transparent.setRGB(0,0,0x80112233);
        when(images.edit(eq("chatgpt"),any(ImageEditInput.class))).thenReturn(generated(transparent));
        var result=processor.process(source,edit);
        assertThat(result.hasAlpha()).isTrue();
        assertThat(ImageRaster.decode(result.bytes()).getRGB(0,0)>>>24).isEqualTo(128);
    }

    @Test void rejectsInvalidSourceAndRectangleBeforeAi() {
        assertThatThrownBy(()->processor.process(new byte[]{1,2,3},new ImageEdit("entire","Change",null,null,null,null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->processor.process(ImageRaster.png(raster(8,6,0)),
                new ImageEdit("region","Change",new ImageEdit.Rect(.9,0,.2,1),null,null,null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(images);
    }

    private BufferedImage raster(int w,int h,int color) {
        var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) image.setRGB(x,y,color);
        return image;
    }
    private GeneratedImage generated(BufferedImage image) {
        return new GeneratedImage(ImageRaster.png(image),"image/png",image.getWidth(),image.getHeight(),true);
    }
}

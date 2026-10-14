package com.tovarika.tech.images.infrastructure;

import com.tovarika.tech.billing.usage.AiCallContext;

import com.tovarika.tech.images.application.GeneratedImage;
import com.tovarika.tech.images.application.GenerationRequest;
import com.tovarika.tech.analyses.domain.AnalysisResult;

/** Transport boundary. The initial implementation is deliberately offline, selected by configuration. */
public interface OpenAiClient {
    GeneratedImage generate(String mainModel, String imageModel, GenerationRequest request);
    GeneratedImage edit(String model, byte[] original, String mediaType, String prompt, int width, int height);
    default GeneratedImage edit(String mainModel, String imageModel, com.tovarika.tech.images.application.ImageEditInput input) {
        throw new UnsupportedOperationException("Advanced image editing is unavailable");
    }
    AnalysisResult analyze(String model, String operationId, byte[] original, String mediaType);
    default boolean stub() { return false; }
    default GeneratedImage generate(String main,String image,GenerationRequest request,AiCallContext context) {
        if(context==null) return generate(main,image,request);
        return context.fallback(main,image,request.width()+"x"+request.height(),stub(),()->generate(main,image,request));
    }
    default GeneratedImage edit(String main,String image,com.tovarika.tech.images.application.ImageEditInput input,AiCallContext context) {
        if(context==null) return edit(main,image,input);
        return context.fallback(main,image,input.width()+"x"+input.height(),stub(),()->edit(main,image,input));
    }
    default AnalysisResult analyze(String model,String id,byte[] original,String mediaType,AiCallContext context) {
        if(context==null) return analyze(model,id,original,mediaType);
        return context.fallback(model,null,null,stub(),()->analyze(model,id,original,mediaType));
    }
}

package com.tovarika.tech.exports;

import com.tovarika.api.publicapi.ExportsApi;
import com.tovarika.api.publicapi.model.*;
import com.tovarika.tech.products.application.AssetLinks;
import com.tovarika.tech.shared.application.ApiFailure;
import com.tovarika.tech.trial.api.WorkspaceIdentityResolver;
import java.net.URI;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExportsController implements ExportsApi {
    private final ExportService service;
    private final ExportStore store;
    private final WorkspaceIdentityResolver identities;
    private final AssetLinks links;
    public ExportsController(ExportService service,ExportStore store,WorkspaceIdentityResolver identities,AssetLinks links) {
        this.service=service;this.store=store;this.identities=identities;this.links=links;
    }
    public ResponseEntity<AsyncOperationDto> createExport(String key,String projectId,CreateExportRequestDto request) {
        var owner=identities.resolve();
        ExportSnapshot.Request input;
        if(request instanceof CreateSingleExportRequestDto r)
            input=request(r.getCardIds(),r.getFormat(),"single",r.getExpectedImages());
        else if(request instanceof CreateZipExportRequestDto r)
            input=request(r.getCardIds(),r.getFormat(),"zip",r.getExpectedImages());
        else throw new ApiFailure(422,"VALIDATION_ERROR","Invalid export request");
        var value=service.start(projectId,key,owner.userId(),input);
        return ResponseEntity.accepted().header("Cache-Control","no-store").body(new AsyncOperationDto(value.jobId(),"queued",
                new ResourceReferenceDto(ResourceReferenceDto.TypeEnum.EXPORT,value.id()),1000));
    }
    private ExportSnapshot.Request request(Collection<String> ids,ExportFormatDto format,String packaging,List<ExportImageExpectationDto> expected) {
        return new ExportSnapshot.Request(ids==null?null:new ArrayList<>(ids),format==null?null:format.getValue(),packaging,
                expected==null?null:expected.stream().map(e->e==null?null:new ExportSnapshot.Expectation(e.getCardId(),e.getVersionId(),e.getImageRevision())).toList());
    }
    public ResponseEntity<ExportDto> getExport(String id) {
        var value=service.get(id,identities.resolve().userId());
        var dto=new ExportDto(value.id(),value.projectId(),new LinkedHashSet<>(value.items().stream().map(ExportSnapshot::cardId).toList()),
                ExportFormatDto.fromValue(value.format()),ExportPackagingDto.fromValue(value.packaging()),JobStatusDto.fromValue(value.status()),
                value.items().stream().map(i->new ExportItemDto(i.cardId(),i.versionId(),i.imageRevision(),i.assetId(),i.mediaType(),i.sizeBytes(),i.fileName())).toList(),
                value.fileName(),value.createdAt().atOffset(ZoneOffset.UTC));
        dto.expiresAt(value.expiresAt().atOffset(ZoneOffset.UTC));
        if(value.errorCode()!=null) dto.errorCode(ErrorCodeDto.fromValue(value.errorCode()));
        if(value.artifactId()!=null && "completed".equals(value.status())) {
            var asset=store.artifact(value.artifactId());var link=links.createExport(value.id(),value.expiresAt());
            dto.artifact(new AssetDto(asset.id(),AssetPurposeDto.fromValue(asset.purpose()),asset.mediaType(),(int)asset.sizeBytes(),
                    URI.create(link.url()),asset.createdAt().atOffset(ZoneOffset.UTC))
                    .width(asset.width()).height(asset.height()).hasAlpha(asset.hasAlpha()).expiresAt(link.expiresAt().atOffset(ZoneOffset.UTC)));
        }
        return ResponseEntity.ok().header("Cache-Control","no-store").body(dto);
    }
}

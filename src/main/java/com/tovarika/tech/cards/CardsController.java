package com.tovarika.tech.cards;

import com.tovarika.api.publicapi.CardsApi;
import com.tovarika.api.publicapi.model.*;
import com.tovarika.tech.products.application.AssetLinks;
import com.tovarika.tech.shared.application.ApiFailure;
import com.tovarika.tech.trial.api.WorkspaceIdentityResolver;
import java.net.URI;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CardsController implements CardsApi {
    private final CardGenerationService cards;
    private final WorkspaceIdentityResolver identities;
    private final AssetLinks links;
    private final com.tovarika.tech.cards.editing.application.CardImageEditingService edits;

    public CardsController(CardGenerationService cards, WorkspaceIdentityResolver identities, AssetLinks links,
            com.tovarika.tech.cards.editing.application.CardImageEditingService edits) {
        this.cards = cards;
        this.identities = identities;
        this.links = links;
        this.edits = edits;
    }

    @Override
    public ResponseEntity<AsyncOperationDto> generateCard(
            String idempotencyKey, String projectId, GenerateCardRequestDto request) {
        var owner = identities.resolve();
        var job = cards.start(projectId, idempotencyKey, owner.userId(), owner.trialSessionId(),
                request.getTemplateId(), request.getPrompt(), request.getAspectRatio().getValue(),
                request.getVariantId(), request.getIdea());
        return ResponseEntity.accepted().body(operation(job));
    }

    @Override
    public ResponseEntity<NextCardDraftDto> getNextCardDraft(String projectId, String templateId) {
        var owner = identities.resolve();
        var draft = cards.nextDraft(projectId, templateId, owner.userId(), owner.trialSessionId());
        var dto = new NextCardDraftDto(NextCardDraftDto.StateEnum.fromValue(draft.state()), draft.position(), 10,
                AspectRatioDto.fromValue(draft.aspectRatio()), java.util.List.of(AspectRatioDto.values()));
        if (draft.templateId() != null) {
            var template = new CardDraftTemplateDto(draft.templateId(), draft.templateName());
            if (draft.referenceId() != null) template.previewUrl(URI.create(links.create(draft.referenceId()).url()));
            dto.template(template);
        }
        dto.variantId(draft.variantId()).defaultIdea(draft.defaultIdea());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(dto);
    }

    @Override
    public ResponseEntity<AsyncOperationDto> retryFailedCard(String key, String projectId, String cardId) {
        var owner = identities.resolve();
        return ResponseEntity.accepted().body(operation(cards.retry(projectId, cardId, key,
                owner.userId(), owner.trialSessionId())));
    }

    @Override
    public ResponseEntity<CardDto> getCard(String projectId, String cardId) {
        var owner = identities.resolve();
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(dto(cards.getCard(projectId, cardId, owner.userId(), owner.trialSessionId())));
    }

    @Override
    public ResponseEntity<CardCollectionDto> listCards(String projectId) {
        var owner = identities.resolve();
        var items = cards.listCards(projectId, owner.userId(), owner.trialSessionId())
                .stream().map(this::dto).toList();
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new CardCollectionDto(items, 10));
    }

    @Override
    public ResponseEntity<AsyncOperationDto> regenerateCard(
            String idempotencyKey, String projectId, String cardId, RegenerateCardRequestDto request) {
        throw outsideMvp();
    }

    @Override
    public ResponseEntity<AsyncOperationDto> editCardRegion(
            String idempotencyKey, String projectId, String cardId, EditCardRegionRequestDto request) {
        var owner=identities.resolve();
        if(!(request.getRegion() instanceof RectangleCardRegionDto rectangle))
            throw new ApiFailure(422,"IMAGE_EDIT_UNAVAILABLE","Uploaded masks are not available");
        var operation=new com.tovarika.tech.cards.editing.domain.ImageEdit("region",request.getPrompt(),rect(rectangle),null,null,null);
        String jobId=edits.start(projectId,cardId,idempotencyKey,owner.userId(),owner.trialSessionId(),
                request.getBaseVersionId(),request.getExpectedImageRevision(),operation,true);
        return ResponseEntity.accepted().body(accepted(jobId,cardId));
    }

    @Override
    public ResponseEntity<CardDto> updateCard(String projectId, String cardId, UpdateCardRequestDto request) {
        throw outsideMvp();
    }

    @Override
    public ResponseEntity<AsyncOperationDto> editCardImage(String key,String projectId,String cardId,ImageEditRequestDto request) {
        var owner=identities.resolve();
        var operation=edit(request.getOperation());
        String jobId=edits.start(projectId,cardId,key,owner.userId(),owner.trialSessionId(),
                request.getBaseVersionId(),request.getExpectedImageRevision(),operation,false);
        return ResponseEntity.accepted().body(accepted(jobId,cardId));
    }

    @Override
    public ResponseEntity<CardDto> undoCardImage(String key,String projectId,String cardId,UndoCardImageRequestDto request) {
        var owner=identities.resolve();
        edits.undo(projectId,cardId,key,owner.userId(),owner.trialSessionId(),request.getBaseVersionId(),request.getExpectedImageRevision());
        return ResponseEntity.ok().header("Cache-Control","no-store")
                .body(dto(cards.getCard(projectId,cardId,owner.userId(),owner.trialSessionId())));
    }

    @Override
    public ResponseEntity<CardDto> redoCardImage(String key,String projectId,String cardId,RedoCardImageRequestDto request) {
        var owner=identities.resolve();
        edits.redo(projectId,cardId,key,owner.userId(),owner.trialSessionId(),request.getBaseVersionId(),request.getExpectedImageRevision());
        return ResponseEntity.ok().header("Cache-Control","no-store")
                .body(dto(cards.getCard(projectId,cardId,owner.userId(),owner.trialSessionId())));
    }

    private AsyncOperationDto accepted(String jobId,String cardId) {
        return new AsyncOperationDto(jobId,"queued",new ResourceReferenceDto(ResourceReferenceDto.TypeEnum.CARD,cardId),1000);
    }

    private com.tovarika.tech.cards.editing.domain.ImageEdit edit(ImageEditOperationDto operation) {
        if(operation instanceof EntireImageEditDto entire)
            return new com.tovarika.tech.cards.editing.domain.ImageEdit("entire",entire.getPrompt(),null,null,null,null);
        if(operation instanceof RegionImageEditDto region)
            return new com.tovarika.tech.cards.editing.domain.ImageEdit("region",region.getPrompt(),rect(region.getRegion()),null,null,null);
        if(operation instanceof EraseCardImageDto erase) {
            var mask=erase.getMask();
            var strokes=mask.getStrokes().stream().map(stroke->new com.tovarika.tech.cards.editing.domain.ImageEdit.Stroke(
                    stroke.getRadius(),stroke.getPoints().stream().map(point->new com.tovarika.tech.cards.editing.domain.ImageEdit.Point(
                            point.getX(),point.getY())).toList())).toList();
            return new com.tovarika.tech.cards.editing.domain.ImageEdit("erase",null,null,null,null,null,
                    new com.tovarika.tech.cards.editing.domain.ImageEdit.BrushMask(mask.getKind(),strokes));
        }
        if(operation instanceof RemoveImageBackgroundDto background)
            return new com.tovarika.tech.cards.editing.domain.ImageEdit("remove_background",null,null,background.getForeground().getValue(),null,null);
        if(operation instanceof ResizeCardImageDto resize)
            return new com.tovarika.tech.cards.editing.domain.ImageEdit("resize",null,null,null,resize.getAspectRatio().getValue(),resize.getMode().getValue());
        throw new ApiFailure(422,"VALIDATION_ERROR","Unsupported image operation");
    }

    private com.tovarika.tech.cards.editing.domain.ImageEdit.Rect rect(RectangleCardRegionDto region) {
        if(region==null || !"rectangle".equals(region.getKind()) || region.getRect()==null)
            throw new ApiFailure(422,"VALIDATION_ERROR","A normalized rectangle is required");
        var r=region.getRect();
        return new com.tovarika.tech.cards.editing.domain.ImageEdit.Rect(
                r.getX().doubleValue(),r.getY().doubleValue(),r.getWidth().doubleValue(),r.getHeight().doubleValue());
    }

    private AsyncOperationDto operation(CardGenerationJob job) {
        return new AsyncOperationDto(job.id(), "queued",
                new ResourceReferenceDto(ResourceReferenceDto.TypeEnum.CARD, job.cardId()), 1000);
    }

    private CardDto dto(CardView card) {
        var dto = new CardDto(card.id(), card.projectId(), card.position(),
                CardStatusDto.fromValue(card.status()), AspectRatioDto.fromValue(card.aspectRatio()),
                card.createdAt().atOffset(ZoneOffset.UTC), card.updatedAt().atOffset(ZoneOffset.UTC));
        dto.templateId(card.templateId()).variantId(card.variantId()).idea(card.idea());
        dto.canUndo(card.canUndo()).canRedo(card.canRedo()).lastImageJobId(card.lastImageJobId());
        if(card.currentVersionId()!=null) dto.currentVersion(new ImageVersionReferenceDto(card.currentVersionId())
                .previousVersionId(card.previousVersionId())).imageRevision(card.imageRevision());
        if (card.errorCode() != null) dto.errorCode(ErrorCodeDto.fromValue(card.errorCode()));
        if (card.image() != null) {
            var link = links.create(card.image().id());
            var image = new AssetDto(card.image().id(), AssetPurposeDto.CARD_IMAGE,
                    card.image().mediaType(), card.image().sizeBytes(), URI.create(link.url()),
                    card.image().createdAt().atOffset(ZoneOffset.UTC));
            image.width(card.image().width()).height(card.image().height())
                    .expiresAt(link.expiresAt().atOffset(ZoneOffset.UTC));
            image.hasAlpha(card.image().hasAlpha());
            dto.image(image);
        }
        return dto;
    }

    private ApiFailure outsideMvp() {
        return new ApiFailure(422, "VALIDATION_ERROR", "This card editing operation is not available");
    }
}

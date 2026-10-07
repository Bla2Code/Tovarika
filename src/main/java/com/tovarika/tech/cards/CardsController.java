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

    public CardsController(CardGenerationService cards, WorkspaceIdentityResolver identities, AssetLinks links) {
        this.cards = cards;
        this.identities = identities;
        this.links = links;
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
        throw outsideMvp();
    }

    @Override
    public ResponseEntity<CardDto> updateCard(String projectId, String cardId, UpdateCardRequestDto request) {
        throw outsideMvp();
    }

    private AsyncOperationDto operation(CardGenerationJob job) {
        return new AsyncOperationDto(job.id(), job.status(),
                new ResourceReferenceDto(ResourceReferenceDto.TypeEnum.CARD, job.cardId()), 1000);
    }

    private CardDto dto(CardView card) {
        var dto = new CardDto(card.id(), card.projectId(), card.position(),
                CardStatusDto.fromValue(card.status()), AspectRatioDto.fromValue(card.aspectRatio()),
                card.createdAt().atOffset(ZoneOffset.UTC), card.updatedAt().atOffset(ZoneOffset.UTC));
        dto.templateId(card.templateId()).variantId(card.variantId()).idea(card.idea());
        if (card.errorCode() != null) dto.errorCode(ErrorCodeDto.fromValue(card.errorCode()));
        if (card.image() != null) {
            var link = links.create(card.image().id());
            var image = new AssetDto(card.image().id(), AssetPurposeDto.CARD_IMAGE,
                    card.image().mediaType(), card.image().sizeBytes(), URI.create(link.url()),
                    card.image().createdAt().atOffset(ZoneOffset.UTC));
            image.width(card.image().width()).height(card.image().height())
                    .expiresAt(link.expiresAt().atOffset(ZoneOffset.UTC));
            dto.image(image);
        }
        return dto;
    }

    private ApiFailure outsideMvp() {
        return new ApiFailure(422, "VALIDATION_ERROR", "This card editing operation is not available");
    }
}

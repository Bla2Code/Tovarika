package com.tovarika.tech.cards;

import com.tovarika.tech.images.infrastructure.OpenAiProperties;
import com.tovarika.tech.shared.application.ApiFailure;
import com.tovarika.tech.templates.TemplateService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@Service
public class CardGenerationService {
    private final CardGenerationStore store;
    private final TemplateService templates;
    private final CardPromptCompiler prompts;
    private final OpenAiProperties openAi;
    private final Clock clock;
    private final ObjectMapper mapper;

    public CardGenerationService(CardGenerationStore store, TemplateService templates,
            CardPromptCompiler prompts, OpenAiProperties openAi, Clock clock, ObjectMapper mapper) {
        this.store = store;
        this.templates = templates;
        this.prompts = prompts;
        this.openAi = openAi;
        this.clock = clock;
        this.mapper = mapper;
    }

    @Transactional
    public CardGenerationJob start(String projectId, String idempotencyKey, String userId, String trialId,
            String templateId, String userPrompt, String aspectRatio, String variantId, String idea) {
        validateKey(idempotencyKey);
        boolean hasTemplate = templateId != null && !templateId.isBlank();
        boolean hasPrompt = userPrompt != null && !userPrompt.isBlank();
        if (hasTemplate == hasPrompt || (hasTemplate && userPrompt != null)
                || (!hasTemplate && (variantId != null || idea != null))) {
            throw new ApiFailure(422, "VALIDATION_ERROR", "Provide templateId with idea/variantId or prompt alone");
        }
        if (idea != null && (idea.isBlank() || idea.length() > 2000)) {
            throw new ApiFailure(422, "VALIDATION_ERROR", "Idea must contain 1 to 2000 characters");
        }
        if (aspectRatio == null || !List.of("1:1", "3:4", "4:5", "16:9").contains(aspectRatio)) {
            throw new ApiFailure(422, "VALIDATION_ERROR", "Unsupported aspect ratio");
        }
        store.lockOwner(userId, trialId);
        var project = store.lockProject(projectId, userId, trialId);
        String ownerScope = userId == null ? trialId : userId;
        var previous = replay(projectId, null, ownerScope, idempotencyKey);
        if (previous != null) return previous;
        requireIdle(projectId);
        if (project.cardCount() >= 10) {
            throw new ApiFailure(409, "CARD_LIMIT_EXCEEDED", "The project already has 10 cards");
        }
        requireAnalysis(project);
        int position = project.cardCount() + 1;
        CardSeries series = hasTemplate ? series(project, templateId) : null;
        JsonNode variant = null;
        String recipe = null, reference = null, finalIdea = null, compiledPrompt;
        if (series != null) {
            variant = selectVariant(series, variantId, position);
            if (variant == null && idea != null) {
                throw new ApiFailure(409, "CARD_VARIANT_UNAVAILABLE", "No prepared variant for this idea");
            }
            recipe = series.recipe();
            reference = series.referenceId();
            if ("live".equalsIgnoreCase(openAi.mode()) && !store.hasReference(reference)) {
                throw new ApiFailure(422, "TEMPLATE_NOT_READY", "Template reference is not configured");
            }
            finalIdea = idea == null ? (variant == null ? null : variant.path("defaultIdea").asText()) : idea.strip();
            compiledPrompt = variant == null
                    ? prompts.fromTemplate(project.description(), recipe)
                    : prompts.fromVariant(project.description(), recipe, variant.path("generationRecipe").toString(),
                            finalIdea, position);
        } else {
            compiledPrompt = prompts.fromUserPrompt(project.description(), userPrompt);
        }
        var now = clock.instant();
        var job = new CardGenerationJob(id("job_"), projectId, id("card_"), "queued", null, 0, now, null, null);
        store.enqueue(job, position, hasTemplate ? templateId : null, reference, aspectRatio, compiledPrompt,
                recipe, variant == null ? null : variant.path("id").asText(), variant == null ? null : variant.toString(),
                finalIdea, series == null ? null : series.json(), project.analysisRevision(), project.description(),
                ownerScope, idempotencyKey, now);
        return job;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NextCardDraft nextDraft(String projectId, String requestedTemplateId, String userId, String trialId) {
        var project = store.readProject(projectId, userId, trialId);
        int position = project.cardCount() + 1;
        if (position > 10) return new NextCardDraft("limit_reached", position, project.defaultAspectRatio(),
                null, null, null, null, null);
        String templateId = requestedTemplateId;
        if (templateId == null) {
            templateId = project.seriesJson() != null ? CardSeries.parse(mapper, project.seriesJson()).templateId()
                    : project.firstTemplateId() != null ? project.firstTemplateId() : project.selectedTemplateId();
        }
        if (templateId == null) return new NextCardDraft("template_required", position, project.defaultAspectRatio(),
                null, null, null, null, null);
        CardSeries series = series(project, templateId);
        JsonNode variant = series.variant(position);
        return new NextCardDraft(variant == null ? "variant_unavailable" : "ready", position,
                project.defaultAspectRatio(), series.templateId(), series.name(), series.referenceId(),
                variant == null ? null : variant.path("id").asText(),
                variant == null ? null : variant.path("defaultIdea").asText());
    }

    @Transactional
    public CardGenerationJob retry(String projectId, String cardId, String key, String userId, String trialId) {
        validateKey(key);
        store.lockOwner(userId, trialId);
        store.lockProject(projectId, userId, trialId);
        String scope = userId == null ? trialId : userId;
        var previous = replay(projectId, cardId, scope, key);
        if (previous != null) return previous;
        requireIdle(projectId);
        CardView card = getCard(projectId, cardId, userId, trialId);
        if (!"error".equals(card.status())) {
            throw new ApiFailure(409, "CARD_RETRY_NOT_ALLOWED", "Only failed cards can be retried");
        }
        var now = clock.instant();
        var job = new CardGenerationJob(id("job_"), projectId, cardId, "queued", null, 0, now, null, null);
        store.retry(job, scope, key, now);
        return job;
    }

    private CardSeries series(CardGenerationStore.ProjectGenerationContext project, String templateId) {
        if (project.seriesJson() != null) {
            CardSeries saved = CardSeries.parse(mapper, project.seriesJson());
            if (saved.templateId().equals(templateId)) return saved;
        }
        CardSeries catalog = CardSeries.parse(mapper, templates.cardSeries(templateId)
                .orElseThrow(() -> new ApiFailure(404, "TEMPLATE_NOT_FOUND", "Template not found")));
        if (project.seriesJson() == null && templateId.equals(project.firstTemplateId()) && project.firstRecipe() != null) {
            return catalog.withLegacyStyle(mapper, project.firstRecipe(), project.firstReference());
        }
        return catalog;
    }

    private JsonNode selectVariant(CardSeries series, String variantId, int position) {
        if (variantId != null) {
            JsonNode requested = series.variant(variantId);
            if (requested == null) throw new ApiFailure(422, "CARD_VARIANT_INVALID", "Variant does not belong to the selected style");
            if (requested.path("position").asInt() != position) {
                throw new ApiFailure(409, "CARD_DRAFT_STALE", "Draft position changed; refresh the form");
            }
            return requested;
        }
        // Preserve the first-card request shape for clients and templates without prepared variants.
        if (position == 1) return series.variant(1);
        if (series.variant(position) == null) {
            throw new ApiFailure(409, "CARD_VARIANT_UNAVAILABLE", "No variant for the next position");
        }
        throw new ApiFailure(422, "CARD_VARIANT_INVALID", "Choose the variant from next-draft");
    }

    private void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new ApiFailure(400, "VALIDATION_ERROR", "Invalid idempotency key");
        }
    }

    private CardGenerationJob replay(String projectId, String cardId, String scope, String key) {
        var previous = store.previous(scope, key);
        if (previous.isEmpty()) return null;
        if (!previous.get().projectId().equals(projectId) || (cardId != null && !previous.get().cardId().equals(cardId))) {
            throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT", "Key belongs to another operation");
        }
        return previous.get();
    }

    private void requireIdle(String projectId) {
        if (store.busy(projectId)) throw new ApiFailure(409, "CARD_BUSY", "Project generation is already running");
    }

    @Transactional(readOnly = true)
    public CardGenerationJob getJob(String id, String userId, String trialId) {
        return store.ownedJob(id, userId, trialId)
                .orElseThrow(() -> new ApiFailure(404, "JOB_NOT_FOUND", "Job not found"));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<CardGenerationJob> findJob(String id, String userId, String trialId) {
        return store.ownedJob(id, userId, trialId);
    }

    @Transactional(readOnly = true)
    public CardView getCard(String projectId, String cardId, String userId, String trialId) {
        return store.ownedCard(projectId, cardId, userId, trialId)
                .orElseThrow(() -> new ApiFailure(404, "CARD_NOT_FOUND", "Card not found"));
    }

    @Transactional(readOnly = true)
    public List<CardView> listCards(String projectId, String userId, String trialId) {
        store.requireOwnedProject(projectId, userId, trialId);
        return store.ownedCards(projectId, userId, trialId);
    }

    private void requireAnalysis(CardGenerationStore.ProjectGenerationContext project) {
        switch (project.productStatus()) {
            case "analysis_ready" -> {
                if (project.description() == null || project.description().isBlank()
                        || project.analysisRevision() == null || project.sourceStorageKey() == null
                        || project.sourceMediaType() == null) {
                    throw new ApiFailure(409, "RESULT_NOT_READY", "Product description is unavailable");
                }
            }
            case "analysis_pending" -> throw new ApiFailure(409, "RESULT_NOT_READY", "Analysis is still processing");
            case "analysis_failed" -> throw new ApiFailure(409, "ANALYSIS_FAILED", "Product analysis failed");
            default -> throw new ApiFailure(409, "RESULT_NOT_READY", "Product analysis is required");
        }
    }

    private String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}

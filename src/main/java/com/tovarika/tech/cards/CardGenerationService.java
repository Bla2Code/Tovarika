package com.tovarika.tech.cards;

import com.tovarika.tech.images.infrastructure.OpenAiProperties;
import com.tovarika.tech.shared.application.ApiFailure;
import com.tovarika.tech.templates.TemplateGenerationView;
import com.tovarika.tech.templates.TemplateService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardGenerationService {
    private final CardGenerationStore store;
    private final TemplateService templates;
    private final CardPromptCompiler prompts;
    private final OpenAiProperties openAi;
    private final Clock clock;

    public CardGenerationService(CardGenerationStore store, TemplateService templates,
            CardPromptCompiler prompts, OpenAiProperties openAi, Clock clock) {
        this.store = store;
        this.templates = templates;
        this.prompts = prompts;
        this.openAi = openAi;
        this.clock = clock;
    }

    @Transactional
    public CardGenerationJob start(String projectId, String idempotencyKey, String userId, String trialId,
            String templateId, String userPrompt, String aspectRatio) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new ApiFailure(400, "VALIDATION_ERROR", "Invalid idempotency key");
        }
        boolean hasTemplate = templateId != null && !templateId.isBlank();
        boolean hasPrompt = userPrompt != null && !userPrompt.isBlank();
        if (hasTemplate == hasPrompt) {
            throw new ApiFailure(422, "VALIDATION_ERROR", "Provide exactly one of templateId or prompt");
        }
        if (aspectRatio == null || !List.of("1:1", "3:4", "4:5", "16:9").contains(aspectRatio)) {
            throw new ApiFailure(422, "VALIDATION_ERROR", "Unsupported aspect ratio");
        }
        store.lockOwner(userId, trialId);
        var project = store.lockProject(projectId, userId, trialId);
        String ownerScope = userId == null ? trialId : userId;
        var previous = store.previous(ownerScope, idempotencyKey);
        if (previous.isPresent()) {
            if (!previous.get().projectId().equals(projectId)) {
                throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT", "Key belongs to another operation");
            }
            return previous.get();
        }
        if (project.cardCount() != 0) {
            throw new ApiFailure(409, "CARD_LIMIT_EXCEEDED", "Only the first card is supported in this MVP");
        }
        requireAnalysis(project);

        TemplateGenerationView template = null;
        String recipe = null;
        String compiledPrompt;
        if (hasTemplate) {
            template = templates.generationTemplate(templateId)
                    .orElseThrow(() -> new ApiFailure(404, "TEMPLATE_NOT_FOUND", "Template not found"));
            if (!template.hasReference() && "live".equalsIgnoreCase(openAi.mode())) {
                throw new ApiFailure(422, "TEMPLATE_NOT_READY", "Template reference image is not configured");
            }
            recipe = template.recipeJson();
            compiledPrompt = prompts.fromTemplate(project.description(), recipe);
        } else {
            compiledPrompt = prompts.fromUserPrompt(project.description(), userPrompt);
        }
        var now = clock.instant();
        var job = new CardGenerationJob(id("job_"), projectId, id("card_"), "queued", null, 0,
                now, null, null);
        store.enqueue(job, template == null ? null : template.id(),
                template == null ? null : template.referenceAssetId(), aspectRatio, compiledPrompt,
                recipe, project.analysisRevision(), ownerScope, idempotencyKey, now);
        return job;
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
                        || project.analysisRevision() == null) {
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

package com.tovarika.tech.templates;

import com.tovarika.api.publicapi.TemplatesApi;
import com.tovarika.api.publicapi.model.PaginationMetaDto;
import com.tovarika.api.publicapi.model.SetTemplateFavoriteRequestDto;
import com.tovarika.api.publicapi.model.TemplateCategoryCollectionDto;
import com.tovarika.api.publicapi.model.TemplateCategoryDto;
import com.tovarika.api.publicapi.model.TemplateDto;
import com.tovarika.api.publicapi.model.TemplatePageDto;
import com.tovarika.tech.auth.api.RequestAuthenticationContext;
import com.tovarika.tech.products.application.AssetLinks;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TemplatesController implements TemplatesApi {
    private final TemplateService templates;
    private final RequestAuthenticationContext authentication;
    private final AssetLinks links;

    public TemplatesController(TemplateService templates, RequestAuthenticationContext authentication, AssetLinks links) {
        this.templates = templates;
        this.authentication = authentication;
        this.links = links;
    }

    @Override
    public ResponseEntity<TemplateCategoryCollectionDto> listTemplateCategories() {
        var items = templates.categories().stream()
                .map(category -> new TemplateCategoryDto(category.id(), category.name()))
                .toList();
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new TemplateCategoryCollectionDto(items));
    }

    @Override
    public ResponseEntity<TemplatePageDto> listTemplates(
            String categoryId, String search, Boolean favoriteOnly, String cursor, Integer limit) {
        var principal = authentication.optionalPrincipal();
        var page = templates.list(principal == null ? null : principal.userId(), categoryId, search,
                Boolean.TRUE.equals(favoriteOnly), cursor, limit == null ? 20 : limit);
        var meta = new PaginationMetaDto(page.limit()).nextCursor(page.nextCursor());
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new TemplatePageDto(page.items().stream().map(this::dto).toList(), meta));
    }

    @Override
    public ResponseEntity<TemplateDto> setTemplateFavorite(
            String templateId, SetTemplateFavoriteRequestDto request) {
        return ResponseEntity.ok(dto(templates.setFavorite(
                authentication.principal().userId(), templateId, request.getFavorite())));
    }

    private TemplateDto dto(TemplateView template) {
        URI thumbnail;
        if (template.referenceAssetId() == null) {
            thumbnail = URI.create(links.templatePlaceholderUrl());
        } else {
            thumbnail = URI.create(links.create(template.referenceAssetId()).url());
        }
        return new TemplateDto(template.id(), template.name(), template.categoryId(), thumbnail, template.favorite());
    }
}

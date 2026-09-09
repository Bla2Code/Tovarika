package com.tovarika.tech.project;

import com.tovarika.api.publicapi.ProjectsApi;
import com.tovarika.api.publicapi.model.AspectRatioDto;
import com.tovarika.api.publicapi.model.AssetDto;
import com.tovarika.api.publicapi.model.AssetPurposeDto;
import com.tovarika.api.publicapi.model.CreateProjectRequestDto;
import com.tovarika.api.publicapi.model.ProjectDto;
import com.tovarika.api.publicapi.model.ProjectPageDto;
import com.tovarika.api.publicapi.model.PaginationMetaDto;
import com.tovarika.api.publicapi.model.UpdateProjectRequestDto;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProjectsController implements ProjectsApi {
    private final ProjectRequestOwnerResolver owners;
    private final ProjectService projects;

    public ProjectsController(ProjectRequestOwnerResolver owners, ProjectService projects) {
        this.owners = owners;
        this.projects = projects;
    }

    @Override
    public ResponseEntity<ProjectDto> createProject(CreateProjectRequestDto request) {
        ProjectView project = projects.create(
                owners.resolve(),
                request.getProductId(),
                request.getName(),
                request.getDefaultAspectRatio() == null ? null : request.getDefaultAspectRatio().getValue());
        return ResponseEntity.status(201).body(dto(project));
    }

    @Override
    public ResponseEntity<ProjectDto> getProject(String projectId) {
        return ResponseEntity.ok(dto(projects.getOwned(projectId, owners.resolve())));
    }

    @Override
    public ResponseEntity<ProjectPageDto> listProjects(String cursor, Integer limit) {
        ProjectPageView page = projects.listOwned(owners.resolveRegisteredUser(), cursor, limit);
        PaginationMetaDto meta = new PaginationMetaDto(page.limit());
        meta.setNextCursor(page.nextCursor());
        return ResponseEntity.ok(new ProjectPageDto(page.items().stream().map(this::dto).toList(), meta));
    }

    @Override
    public ResponseEntity<ProjectDto> updateProject(String projectId, UpdateProjectRequestDto request) {
        throw ProjectException.projectNotFound();
    }

    @Override
    public ResponseEntity<Void> deleteProject(String projectId) {
        throw ProjectException.projectNotFound();
    }

    private ProjectDto dto(ProjectView project) {
        ProjectDto dto = new ProjectDto(
                project.id(),
                project.name(),
                project.productId(),
                AspectRatioDto.fromValue(project.defaultAspectRatio()),
                project.cardCount(),
                project.createdAt().atOffset(ZoneOffset.UTC),
                project.updatedAt().atOffset(ZoneOffset.UTC));
        dto.setSelectedTemplateId(project.selectedTemplateId());
        if (project.previewImage() != null) {
            dto.setPreviewImage(assetDto(project.previewImage()));
        }
        return dto;
    }

    private AssetDto assetDto(ProjectAssetView asset) {
        AssetDto dto = new AssetDto(
                asset.id(),
                AssetPurposeDto.fromValue(asset.purpose()),
                asset.mediaType(),
                asset.sizeBytes(),
                asset.url(),
                asset.createdAt().atOffset(ZoneOffset.UTC));
        dto.setWidth(asset.width());
        dto.setHeight(asset.height());
        dto.setExpiresAt(asset.expiresAt() == null ? null : asset.expiresAt().atOffset(ZoneOffset.UTC));
        return dto;
    }
}

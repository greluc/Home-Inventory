/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import io.swagger.v3.oas.annotations.tags.Tag;
import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.portability.api.ExportService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asking for everything you have (REQ-PORT-003, REQ-PORT-005).
 *
 * <p>{@code 202} and a job resource, never a long-held response: a tenant with ten thousand items
 * and their photographs is minutes of work, and this is the feature somebody uses when they are
 * leaving — the worst moment for it to time out.
 *
 * <p>Guarded by {@code TENANT_EXPORT}: an export is every row the tenant has in one file, so the
 * question is not "may you read an item" but "may you take the lot", and those are different
 * questions even when the same person answers yes to both. It is {@code ADMIN}'s and {@code
 * OWNER}'s, and a membership confined to part of the location tree does not hold it whatever its
 * role — there is no archive of a shelf. These endpoints asked for {@code TENANT_READ} until
 * 2026-09-20, which let a scoped {@code VIEWER} download the whole inventory (ADR-0068, O27).
 */
@Tag(name = "Export", description = "Taking a copy of everything, including the photographs.")
@RestController
@RequestMapping("/api/v1/export-jobs")
@RequiredArgsConstructor
public class ExportJobController {

  private final ExportService exports;

  /**
   * Asks for an export (REQ-PORT-005).
   *
   * @param user the authenticated caller
   * @return {@code 202} with the queued job and a {@code Location} pointing at it
   */
  @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TENANT_EXPORT)
  @CanFail(ProblemType.VALIDATION_FAILED)
  public ResponseEntity<ExportService.ExportJobView> requestExport(
      @AuthenticationPrincipal AuthenticatedUser user) {
    ExportService.ExportJobView job = exports.request(user.userId());
    return ResponseEntity.accepted()
        .header(HttpHeaders.LOCATION, "/api/v1/export-jobs/" + job.id())
        .body(job);
  }

  /**
   * This tenant's export jobs, newest first.
   *
   * @param limit how many at most; capped at 200
   * @return the jobs
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TENANT_EXPORT)
  @CanFail(ProblemType.MALFORMED_REQUEST)
  public List<ExportService.ExportJobView> listJobs(
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return exports.jobs(limit);
  }

  /**
   * One export job, which is how progress is watched.
   *
   * @param id which one
   * @return the job
   */
  @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TENANT_EXPORT)
  @CanFail(ProblemType.NOT_FOUND)
  public ExportService.ExportJobView job(@PathVariable UUID id) {
    return exports.job(id);
  }

  /**
   * The archive itself.
   *
   * <p>Streamed rather than buffered: it is tens of megabytes, and holding it in memory to serve it
   * would put the ceiling on how much a tenant may own.
   *
   * @param id the job
   * @return the ZIP
   * @throws IOException when the store cannot be read
   */
  @GetMapping(path = "/{id}/content", produces = "application/zip")
  @RequiresPermission(Permission.TENANT_EXPORT)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.EXPORT_NOT_READY})
  public ResponseEntity<InputStreamResource> content(@PathVariable UUID id) throws IOException {
    ExportService.ExportJobView job = exports.job(id);
    return ResponseEntity.status(HttpStatus.OK)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"home-inventory-export-" + id + ".zip\"")
        .contentType(MediaType.parseMediaType("application/zip"))
        .contentLength(job.byteSize() == null ? -1 : job.byteSize())
        .body(new InputStreamResource(exports.open(id)));
  }
}

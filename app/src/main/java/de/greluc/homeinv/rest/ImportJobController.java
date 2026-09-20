/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.portability.api.ImportService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Bringing an inventory back (REQ-PORT-003, REQ-PORT-004, REQ-PORT-007).
 *
 * <p>The mirror of {@link ExportJobController} and guarded by the same permission, for the same
 * reason: {@code TENANT_EXPORT} is the right to move a whole tenant, in either direction, and a
 * membership confined to part of the location tree does not hold it (ADR-0068). Reading an archive
 * into a tenant is if anything the more consequential half — it <b>writes</b>, and the archive
 * wins wherever the two disagree.
 *
 * <p>The upload answers {@code 202} with a job. Reading an archive is minutes of work; a request
 * that did it would hold a connection open for minutes on behalf of whoever uploaded a file, which
 * is both a bad experience and a way to occupy the instance.
 */
@RestController
@RequestMapping("/api/v1/import-jobs")
@RequiredArgsConstructor
public class ImportJobController {

  private final ImportService imports;

  /**
   * Uploads an archive and queues it (REQ-PORT-007).
   *
   * @param user the authenticated caller
   * @param file the archive, as produced by an export
   * @param dryRun whether to walk the whole import and then roll it back, writing nothing
   * @return {@code 202} with the queued job and a {@code Location} pointing at it
   * @throws IOException when the upload cannot be stored
   */
  // No `consumes`, deliberately, and `MediaController` does the same. Declaring
  // `multipart/form-data` makes Spring answer 415 before the security aspect
  // runs, so an endpoint that should refuse a caller with `403` refuses the
  // content type instead -- which `EndpointNegativeCoverageIT` fails, and is
  // right to: an endpoint that fails before the access decision is made has not
  // made one.
  @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TENANT_EXPORT)
  @CanFail({
    ProblemType.VALIDATION_FAILED,
    ProblemType.MALFORMED_REQUEST,
    ProblemType.UNSUPPORTED_MEDIA_TYPE
  })
  public ResponseEntity<ImportService.ImportJobView> upload(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam("file") MultipartFile file,
      @RequestParam(required = false, defaultValue = "false") boolean dryRun)
      throws IOException {
    try (InputStream bytes = file.getInputStream()) {
      ImportService.ImportJobView job = imports.accept(user.userId(), bytes, dryRun);
      return ResponseEntity.accepted()
          .header(HttpHeaders.LOCATION, "/api/v1/import-jobs/" + job.id())
          .body(job);
    }
  }

  /**
   * This tenant's import jobs, newest first.
   *
   * @param limit how many at most; capped at 200
   * @return the jobs
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TENANT_EXPORT)
  @CanFail(ProblemType.MALFORMED_REQUEST)
  public List<ImportService.ImportJobView> listJobs(
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return imports.jobs(limit);
  }

  /**
   * One import job, which is how progress is watched and the report is read.
   *
   * @param id which one
   * @return the job
   */
  @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TENANT_EXPORT)
  @CanFail(ProblemType.NOT_FOUND)
  public ImportService.ImportJobView job(@PathVariable UUID id) {
    return imports.job(id);
  }
}
